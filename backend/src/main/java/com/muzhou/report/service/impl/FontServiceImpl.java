package com.muzhou.report.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.muzhou.report.common.BizException;
import com.muzhou.report.common.PageResult;
import com.muzhou.report.config.MzProperties;
import com.muzhou.report.entity.MzFont;
import com.muzhou.report.export.PdfFonts;
import com.muzhou.report.mapper.MzFontMapper;
import com.muzhou.report.service.FontService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 上传字体管理服务实现。见 docs/CONTRACT.md §3.6。
 *
 * <p><b>字体文件的正本在库里</b>（{@code mz_font.file_data}），各节点用到时再落一份到
 * {@code muzhou.report.font.dir} 当缓存 —— PDF 引擎（{@code BaseFont.createFont}）要的是一个
 * 路径，不是一段字节。多节点部署时这是唯一站得住的做法：库是各节点共享的，本地磁盘不是。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FontServiceImpl extends ServiceImpl<MzFontMapper, MzFont> implements FontService {

    /** 收得下的字体格式。三种都是 PDF 引擎读得懂的轮廓字体；woff / woff2 只有浏览器认，内嵌不进 PDF。 */
    private static final Set<String> FORMATS = Set.of("ttf", "otf", "ttc");

    /**
     * 字体名里不许出现的字符。
     *
     * <p>这个名字要同时活在三个地方：CSS 的 {@code font-family}（引号、逗号是语法）、
     * xlsx 的字体名、以及 {@code BaseFont.createFont} 的「路径,序号」写法（逗号又是语法）。
     * 与其在三处各转一次义，不如在入口就不收 —— 字体名本来也没人会往里塞这些。
     */
    private static final String NAME_FORBIDDEN = "\"',<>\\/;{}";

    /** 字体名长度上限。Excel 的字体名放不下太长的串，取个各处都安全的数。 */
    private static final int NAME_MAX = 32;

    private final MzProperties props;

    /* ------------------------------ FontProvider ------------------------------ */

    /**
     * 导出那一层唯一要的东西：字体名 -> 本节点上可用的字体文件路径。
     *
     * <p>只认**启用**的字体 —— 停用等于「这款字体不存在」，那些格子该退回默认字体，
     * 与设计器字体清单里不再出现它是同一件事的两头。
     *
     * <p>查的是 {@code uk_font_name} 这个唯一索引，而且 {@link PdfFonts} 一次导出只为
     * 模板里真出现过的那几个名字各问一次，所以这里不必再加一层缓存 ——
     * 加了反而要处理「字体停用了怎么让缓存失效」。
     */
    @Override
    public String pathOf(String fontName) {
        if (!StringUtils.hasText(fontName)) {
            return null;
        }
        LambdaQueryWrapper<MzFont> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MzFont::getFontName, fontName).eq(MzFont::getStatus, 1);
        MzFont font = getOne(wrapper, false);
        if (font == null) {
            return null;
        }
        Path file = ensureCached(font);
        return file == null ? null : pathOf(font, file);
    }

    /* -------------------------------- CRUD -------------------------------- */

    @Override
    public PageResult<MzFont> page(long pageNo, long pageSize, String name) {
        LambdaQueryWrapper<MzFont> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(name)) {
            wrapper.like(MzFont::getFontName, name);
        }
        wrapper.orderByAsc(MzFont::getFontName);
        return PageResult.of(page(new Page<>(pageNo, pageSize), wrapper));
    }

    @Override
    public List<MzFont> listAll() {
        LambdaQueryWrapper<MzFont> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MzFont::getStatus, 1);
        wrapper.orderByAsc(MzFont::getFontName);
        return list(wrapper);
    }

    @Override
    public MzFont get(String id) {
        MzFont font = getById(id);
        if (font == null) {
            throw new BizException("字体不存在");
        }
        return font;
    }

    @Override
    public String upload(byte[] bytes, String originalFilename, String fontName, Integer ttcIndex, String remark) {
        String name = fontName == null ? "" : fontName.trim();
        checkName(name, null);
        String format = formatOf(originalFilename);
        if (bytes == null || bytes.length == 0) {
            throw new BizException("字体文件是空的");
        }
        if (bytes.length > props.getFont().getMaxBytes()) {
            throw new BizException("字体文件超过上限 " + props.getFont().getMaxBytes() / 1024 / 1024 + "MB");
        }

        MzFont font = new MzFont();
        // 先自己发 id：本地缓存文件名是 {id}.{后缀}，跟字体名解耦，改名不必动磁盘。
        // 自己发了之后 ASSIGN_UUID 不会再覆盖它
        font.setId(IdWorker.get32UUID());
        font.setFontName(name);
        font.setFileName(StringUtils.hasText(originalFilename) ? originalFilename : name + "." + format);
        font.setFontFormat(format);
        font.setTtcIndex("ttc".equals(format) && ttcIndex != null && ttcIndex > 0 ? ttcIndex : 0);
        font.setFileSize((long) bytes.length);
        font.setFileData(bytes);
        font.setRemark(remark);
        font.setStatus(1);

        // 能不能印出来只有真要用它的那个引擎说了算，所以在这儿真加载一遍
        checkLoadable(font, bytes);

        // 唯一索引不带 deleted 条件：删掉之后再传同名的会撞唯一键，先把那具尸体清掉
        baseMapper.purgeDeletedByName(name);
        save(font);
        // 落库之后顺手在本节点缓存一份，省掉「刚传完自己又去库里读一遍」。
        // 这一步失败只记一条日志：正本已经在库里了，下次用到 ensureCached 会自己补上
        try {
            write(font, bytes);
        } catch (Exception e) {
            log.warn("字体[{}]的本节点缓存写入失败，下次用到时再落: {}", name, e.getMessage());
        }
        log.info("上传字体[{}] {} bytes", name, bytes.length);
        return font.getId();
    }

    /**
     * 校验这款字体 PDF 引擎用不用得了，用不了就抛业务异常。
     *
     * <p>ttf / otf **直接拿字节校验、不落盘**：{@code BaseFont.createFont} 失败时文件句柄不一定
     * 收得回来（内存映射读的），Windows 下随后就删不掉，于是每传一次坏字体就留一个孤儿文件
     * —— 见 {@link PdfFonts#probe}。.ttc 没法从字节校验（字体集要按「路径,序号」取第几款），
     * 只能先落盘，删不掉时留下的那点垃圾认了。
     */
    private void checkLoadable(MzFont font, byte[] bytes) {
        if (!"ttc".equals(font.getFontFormat())) {
            try {
                PdfFonts.probe(bytes, font.getFontFormat());
            } catch (Exception e) {
                throw rejected(font, e);
            }
            return;
        }
        // .ttc 没法从字节校验，只能先落盘。校验不过时那个文件多半还被引擎锁着（见上面那段），
        // 删不掉就留着 —— 它不会被任何一行引用到，只是一点垃圾
        Path file = write(font, bytes);
        try {
            PdfFonts.load(pathOf(font, file));
        } catch (Exception e) {
            deleteQuietly(file);
            throw rejected(font, e);
        }
    }

    /** 把引擎抛的异常翻成一句用户看得懂的话。 */
    private BizException rejected(MzFont font, Exception e) {
        log.warn("字体[{}]校验不通过: {}", font.getFileName(), e.getMessage());
        if (PdfFonts.embeddingRestricted(e)) {
            // 文件本身好好的，是字体厂商在文件里声明了不许嵌入 —— 让人「换个格式再传」毫无意义
            return new BizException("这款字体的授权不允许嵌入文档（字体文件里的 fsType 位这么写的），"
                    + "而 PDF 里的中文必须内嵌字体才显示得出来，所以用不了。"
                    + "请改用允许嵌入的字体：多数开源中文字体（思源黑体/宋体、文泉驿等）都可以，"
                    + "商业字体则要找字体厂商拿允许嵌入的那一版");
        }
        // 别把服务器上的绝对路径带给用户：那是本节点的缓存路径，对他没有意义
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String prefix = Path.of(props.getFont().getDir()).toAbsolutePath() + File.separator;
        return new BizException("这个字体文件 PDF 引擎读不了，换一份试试（建议 .ttf）: "
                + msg.replace(prefix, "").trim());
    }

    @Override
    public boolean update(MzFont font) {
        MzFont old = get(font.getId());
        String name = font.getFontName() == null ? "" : font.getFontName().trim();
        checkName(name, font.getId());
        if (!name.equals(old.getFontName())) {
            // 改名等于占用一个新名字，同样要躲开已删除的同名行
            baseMapper.purgeDeletedByName(name);
        }
        // 文件相关的几项一律以库里那份为准：换文件走「删了重传」，见 FontService#update 的注释。
        // fileData 留空，NOT_NULL 的更新策略会跳过它，不会把字体内容洗成 null
        MzFont patch = new MzFont();
        patch.setId(old.getId());
        patch.setFontName(name);
        patch.setRemark(font.getRemark());
        patch.setStatus(font.getStatus() == null ? 1 : font.getStatus());
        return updateById(patch);
    }

    /**
     * 删字体：库里的行逻辑删除（留个痕、正本连同字节一起失效），本节点的缓存文件真删掉。
     *
     * <p><b>别的节点上那份缓存不管</b>：删不着，也不必删 —— 解析一律先查库
     * （{@link #pathOf(String)}），行没了就再也不会去碰那个文件；而缓存文件名带着 id，
     * 重新传一个同名字体拿到的是新 id、新文件，绝不会读到旧内容。留下的只是一点磁盘占用。
     */
    @Override
    public boolean remove(String id) {
        MzFont font = get(id);
        deleteQuietly(cachePath(font));
        return removeById(id);
    }

    @Override
    public FontFile readFile(String id) {
        MzFont font = get(id);
        Path file = ensureCached(font);
        if (file == null) {
            throw new BizException("字体文件已丢失: " + font.getFileName());
        }
        try {
            return new FontFile(font.getFileName(), contentType(font.getFontFormat()), Files.readAllBytes(file));
        } catch (IOException e) {
            throw new BizException("字体文件读取失败: " + e.getMessage());
        }
    }

    /* -------------------------------- 本地缓存 -------------------------------- */

    /**
     * 保证本节点上有这款字体的文件，返回它的路径；库里连字节都没有时返回 null。
     *
     * <p>这是「多节点」这件事的全部落点：A 节点传的字体，B 节点第一次要用时在这里把它从库里
     * 拉下来落一份。之后就一直命中本地文件，不再读库 —— 一款字体十几 MB，每次导出都读一遍
     * 是不能接受的。
     *
     * <p>顺带按字节数校一次：进程被杀在写文件中间会留下半截文件，大小对不上就当没有、重新落一份。
     */
    private Path ensureCached(MzFont font) {
        Path file = cachePath(font);
        try {
            if (Files.isRegularFile(file)
                    && (font.getFileSize() == null || Files.size(file) == font.getFileSize())) {
                return file;
            }
        } catch (IOException e) {
            log.warn("字体缓存[{}]读取失败，重新落一份: {}", file, e.getMessage());
        }
        byte[] data = baseMapper.selectData(font.getId());
        if (data == null || data.length == 0) {
            log.warn("字体[{}]在库里没有文件内容", font.getFontName());
            return null;
        }
        return write(font, data);
    }

    /**
     * 把字节写成本节点的缓存文件。
     *
     * <p>**先写临时文件再原子改名**：同一节点上两次导出可能同时发现「本地还没有这款字体」，
     * 直接往目标文件上写的话，后一个读到的是前一个写了一半的内容 ——
     * 而字体文件解析失败的表现是「这一格退回默认字体」，最难往这上面想。
     */
    private Path write(MzFont font, byte[] data) {
        Path file = cachePath(font);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp-" + IdWorker.get32UUID());
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // 有些文件系统（部分网络盘）不支持原子改名，退一步也比直接往目标文件上写强
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return file;
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new BizException("字体文件写入失败[" + file + "]: " + e.getMessage());
        }
    }

    /** 本节点上这款字体的缓存文件位置。文件名用 id 而不是字体名：改名不必动磁盘，也不怕中文路径。 */
    private Path cachePath(MzFont font) {
        return dir().resolve(font.getId() + "." + font.getFontFormat());
    }

    /** {@code BaseFont.createFont} 认的那种路径：.ttc 要「路径,序号」指定用其中第几款。 */
    private String pathOf(MzFont font, Path file) {
        int index = font.getTtcIndex() == null ? 0 : font.getTtcIndex();
        String path = file.toAbsolutePath().toString();
        return "ttc".equals(font.getFontFormat()) ? path + "," + index : path;
    }

    /** 字体缓存目录，不存在就建。 */
    private Path dir() {
        Path dir = Path.of(props.getFont().getDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new BizException("字体目录创建失败[" + dir.toAbsolutePath() + "]: " + e.getMessage());
        }
        return dir;
    }

    /* -------------------------------- 校验 -------------------------------- */

    private String formatOf(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        String ext = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!FORMATS.contains(ext)) {
            throw new BizException("只支持 .ttf / .otf / .ttc 字体文件");
        }
        return ext;
    }

    private String contentType(String format) {
        return switch (format == null ? "" : format) {
            case "otf" -> "font/otf";
            case "ttc" -> "font/collection";
            default -> "font/ttf";
        };
    }

    /**
     * 字体名要同时当 CSS 的 font-family、xlsx 的字体名和单元格的 {@code ff} 用，
     * 起得随便会在出纸那头才露馅，所以在入口拦。
     */
    private void checkName(String name, String selfId) {
        if (!StringUtils.hasText(name)) {
            throw new BizException("请填写字体名");
        }
        if (name.length() > NAME_MAX) {
            throw new BizException("字体名不能超过 " + NAME_MAX + " 个字符");
        }
        for (int i = 0; i < name.length(); i++) {
            if (NAME_FORBIDDEN.indexOf(name.charAt(i)) >= 0) {
                throw new BizException("字体名不能包含 " + NAME_FORBIDDEN + " 这些字符");
            }
        }
        LambdaQueryWrapper<MzFont> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MzFont::getFontName, name);
        if (StringUtils.hasText(selfId)) {
            wrapper.ne(MzFont::getId, selfId);
        }
        if (count(wrapper) > 0) {
            throw new BizException("字体名已存在: " + name);
        }
    }

    /** 删文件失败只记一条日志：它只是本节点的缓存，留个孤儿不该让整个请求失败。 */
    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("字体缓存文件删除失败[{}]: {}", file, e.getMessage());
        }
    }
}

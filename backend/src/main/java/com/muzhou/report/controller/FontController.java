package com.muzhou.report.controller;

import com.muzhou.report.common.BizException;
import com.muzhou.report.common.PageResult;
import com.muzhou.report.common.Result;
import com.muzhou.report.entity.MzFont;
import com.muzhou.report.service.FontService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * 上传字体管理接口。见 docs/CONTRACT.md §3.6。
 * 异常统一由 {@link com.muzhou.report.common.GlobalExceptionHandler} 处理，此处不做 try/catch。
 */
@RestController
@RequestMapping("/api/font")
@RequiredArgsConstructor
public class FontController {

    private final FontService service;

    /** 分页查询字体（name 按字体名模糊匹配）。 */
    @GetMapping("/page")
    public Result<PageResult<MzFont>> page(@RequestParam(defaultValue = "1") long pageNo,
                                           @RequestParam(defaultValue = "10") long pageSize,
                                           @RequestParam(required = false) String name) {
        return Result.ok(service.page(pageNo, pageSize, name));
    }

    /**
     * 全部启用的字体。前端拿它做两件事：并进设计器的字体下拉、按 id 拼出 {@code @font-face}
     * 的地址（见 {@code frontend/src/utils/fontList.js}）。
     */
    @GetMapping("/list")
    public Result<List<MzFont>> list() {
        return Result.ok(service.listAll());
    }

    @GetMapping("/{id}")
    public Result<MzFont> get(@PathVariable String id) {
        return Result.ok(service.get(id));
    }

    /**
     * 上传字体。
     *
     * @param ttcIndex .ttc 字体集里用第几款，其余格式忽略
     */
    @PostMapping("/upload")
    public Result<String> upload(@RequestPart("file") MultipartFile file,
                                 @RequestParam String fontName,
                                 @RequestParam(required = false) Integer ttcIndex,
                                 @RequestParam(required = false) String remark) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择字体文件");
        }
        return Result.ok(service.upload(file.getBytes(), file.getOriginalFilename(), fontName, ttcIndex, remark));
    }

    /** 改字体名 / 备注 / 状态。换文件请删掉重传，见 {@link FontService#update}。 */
    @PutMapping
    public Result<Boolean> update(@RequestBody MzFont font) {
        return Result.ok(service.update(font));
    }

    /** 删除字体：库里逻辑删除，磁盘上的文件真删掉。 */
    @DeleteMapping("/{id}")
    public Result<Boolean> remove(@PathVariable String id) {
        return Result.ok(service.remove(id));
    }

    /**
     * 下载字体文件，供浏览器的 {@code @font-face} 取用。
     *
     * <p>这条**不包 {@code Result}**，出的是字节流 —— 前端 axios 的拦截器只对 JSON 解包，
     * 而这个地址压根不经过 axios，是浏览器自己按 CSS 里的 URL 去拉的。
     *
     * <p>挂长缓存是因为**每开一个页面都会拉一遍**（设计器、预览、每一次刷新），而字体文件动辄
     * 十几 MB。内容是不会变的：文件名按 id 生成、id 不复用，改字体名也不动磁盘，
     * 换文件走的是「删了重传」（新 id 就是新地址），所以缓存永远不会指到过期的内容上。
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable String id) {
        FontService.FontFile font = service.readFile(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, font.contentType())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(font.bytes());
    }
}

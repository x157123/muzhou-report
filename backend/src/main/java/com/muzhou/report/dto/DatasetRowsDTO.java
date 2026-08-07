package com.muzhou.report.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 一次取数的结果：行数据 + 总条数。见 docs/CONTRACT.md §3.2 数据集的 resultType。
 *
 * <p>{@code total} 只有分页型数据集（{@code resultType=page}）才有；集合型是 null ——
 * 「整份取回」这件事本身就意味着 {@code rows.size()} 就是全部，没有第二个总数可言。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DatasetRowsDTO implements Serializable {

    private List<Map<String, Object>> rows;

    /** 分页型数据集的总条数；集合型为 null。 */
    private Long total;

    public static DatasetRowsDTO of(List<Map<String, Object>> rows) {
        return new DatasetRowsDTO(rows, null);
    }
}

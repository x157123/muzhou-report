package com.muzhou.report.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 数据库列元数据。见 docs/CONTRACT.md §3.1 GET /{id}/columns。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ColumnMetaDTO implements Serializable {

    /** 列名（小写）。 */
    private String name;

    /** string|number|date|boolean。 */
    private String type;

    /** 列注释，取不到为空字符串。 */
    private String comment;
}

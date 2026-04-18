package com.czh.datasmart.govern.datasource.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:50
 * @Description DataSmart Govern Backend - SampleRowPreview.java
 * @Version:1.0.0
 *
 * 样本行预览。
 * 这里不试图做强类型列模型，而是保留“列名 -> 值”的通用结构，
 * 以便在不同数据库方言、不同字段类型下都能快速返回第一版数据预览。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SampleRowPreview {

    /**
     * 样本行序号。
     */
    private Integer rowNumber;

    /**
     * 样本数据。
     */
    private Map<String, Object> values;
}

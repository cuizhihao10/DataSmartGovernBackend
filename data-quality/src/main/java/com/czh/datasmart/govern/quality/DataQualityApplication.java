/**
 * @Author : Cui
 * @Date: 2026/4/18 21:30
 * @Description DataSmart Govern Backend - DataQualityApplication.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数据质量模块启动类。
 * 当前模块的职责可以概括为两部分：
 * 1. 管理“什么叫合格”的质量规则。
 * 2. 执行“是否合格”的质量检测并沉淀检测报告。
 *
 * 虽然启动类本身没有复杂业务逻辑，但它清楚表明：
 * `data-quality` 不是 task-management 的一个子包，而是独立部署、独立扩展的业务模块。
 */
@SpringBootApplication
public class DataQualityApplication {

    /**
     * Spring Boot 标准入口。
     * 当前保持最小启动逻辑，方便后续把预热、监听器和扩展初始化放到更合适的位置。
     */
    public static void main(String[] args) {
        SpringApplication.run(DataQualityApplication.class, args);
    }
}

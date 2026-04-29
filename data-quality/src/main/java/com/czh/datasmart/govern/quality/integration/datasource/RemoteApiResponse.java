/**
 * @Author : Cui
 * @Date: 2026/04/27 22:00
 * @Description DataSmart Govern Backend - RemoteApiResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.datasource;

import lombok.Data;

/**
 * 跨服务调用时接收远程统一响应体的本地模型。
 *
 * <p>各业务模块当前都采用 code、message、data 的响应结构。
 * data-quality 在调用 datasource-management 时只需要理解这个外层结构，
 * 再把 data 映射成自己关心的局部契约模型即可。
 */
@Data
public class RemoteApiResponse<T> {

    /**
     * 业务结果码，0 代表成功。
     */
    private Integer code;

    /**
     * 远程服务返回的提示信息。
     */
    private String message;

    /**
     * 远程服务真实业务数据。
     */
    private T data;

    /**
     * 判断远程响应是否表示成功。
     */
    public boolean successful() {
        return Integer.valueOf(0).equals(code);
    }
}

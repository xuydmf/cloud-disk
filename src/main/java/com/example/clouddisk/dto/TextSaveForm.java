package com.example.clouddisk.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 文本文件保存请求
 */
@Data
public class TextSaveForm implements Serializable {

    private String content;
}

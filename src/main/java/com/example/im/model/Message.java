package com.example.im.model;

import lombok.Data;

@Data
public class Message {
    private String fromUser;
    private String content;
    private String groupId;
    private long timestamp;
} 
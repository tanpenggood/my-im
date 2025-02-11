package com.example.im.controller;

import com.example.im.netty.WebSocketServerHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    @GetMapping
    public Set<String> getAllGroups() {
        return WebSocketServerHandler.getAllGroupIds();
    }
} 
package com.example.im.netty;

import com.example.im.model.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

@Slf4j
public class WebSocketServerHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    
    private static final int MAX_GROUPS = 10;
    private static final String DEFAULT_GROUP = "G00001";
    
    private static final Map<String, Map<String, Channel>> groupChannels = new ConcurrentHashMap<>();
    private static final Map<Channel, UserInfo> channelInfo = new ConcurrentHashMap<>();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Map<String, Set<String>> groupUsers = new ConcurrentHashMap<>();

    static {
        // 在静态初始化块中创建默认群组
        groupChannels.put(DEFAULT_GROUP, new ConcurrentHashMap<>());
        groupUsers.put(DEFAULT_GROUP, ConcurrentHashMap.newKeySet());
        log.info("Created default group: {}", DEFAULT_GROUP);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
        Message message = objectMapper.readValue(frame.text(), Message.class);
        
        // 如果是加入群组的消息
        if (message.getContent() != null && message.getContent().startsWith("JOIN_GROUP:")) {
            String groupId = message.getContent().substring("JOIN_GROUP:".length());
            
            // 如果是创建新群组（不是加入默认群组）且已达到上限，则拒绝
            if (!DEFAULT_GROUP.equals(groupId) && 
                !groupChannels.containsKey(groupId) && 
                groupChannels.size() >= MAX_GROUPS) {
                
                // 发送错误消息给客户端
                Message errorMessage = new Message();
                errorMessage.setFromUser("system");
                errorMessage.setContent("ERROR:MAX_GROUPS_REACHED");
                errorMessage.setGroupId(groupId);
                errorMessage.setTimestamp(System.currentTimeMillis());
                
                ctx.channel().writeAndFlush(new TextWebSocketFrame(objectMapper.writeValueAsString(errorMessage)));
                return;
            }
            
            // 添加用户到群组
            addUserToGroup(groupId, message.getFromUser(), ctx.channel());
            channelInfo.put(ctx.channel(), new UserInfo(message.getFromUser(), groupId));
            
            // 添加用户到群组用户集合
            groupUsers.computeIfAbsent(groupId, k -> ConcurrentHashMap.newKeySet()).add(message.getFromUser());
            
            // 广播在线用户列表更新
            broadcastOnlineUsers(groupId);
            
            log.info("User {} joined group {}", message.getFromUser(), groupId);
            return;
        }

        // 广播消息
        Map<String, Channel> channels = groupChannels.get(message.getGroupId());
        if (channels != null) {
            String messageJson = objectMapper.writeValueAsString(message);
            channels.values().forEach(channel -> 
                channel.writeAndFlush(new TextWebSocketFrame(messageJson))
            );
            log.info("Message broadcast to group {}: {}", message.getGroupId(), messageJson);
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        log.info("Handler Added: {}", ctx.channel().id());
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        log.info("Handler Removed: {}", ctx.channel().id());
        UserInfo userInfo = channelInfo.remove(ctx.channel());
        if (userInfo != null) {
            Map<String, Channel> groupUsers = groupChannels.get(userInfo.groupId);
            if (groupUsers != null) {
                groupUsers.remove(userInfo.userId);
                if (groupUsers.isEmpty()) {
                    groupChannels.remove(userInfo.groupId);
                }
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("WebSocket Error: ", cause);
        ctx.close();
    }

    // 添加用户到群组
    public static void addUserToGroup(String groupId, String userId, Channel channel) {
        groupChannels.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
                    .put(userId, channel);
    }

    // 获取所有群组ID
    public static Set<String> getAllGroupIds() {
        Set<String> allGroups = new LinkedHashSet<>();
        allGroups.add(DEFAULT_GROUP); // 确保默认群组在第一位
        allGroups.addAll(groupChannels.keySet().stream()
                .filter(id -> !id.equals(DEFAULT_GROUP))
                .collect(Collectors.toSet()));
        return allGroups;
    }

    // 添加广播在线用户列表的方法
    private void broadcastOnlineUsers(String groupId) {
        try {
            Set<String> users = groupUsers.get(groupId);
            if (users != null) {
                Message userListMessage = new Message();
                userListMessage.setFromUser("system");
                userListMessage.setGroupId(groupId);
                userListMessage.setContent("ONLINE_USERS:" + String.join(",", users));
                userListMessage.setTimestamp(System.currentTimeMillis());

                String messageJson = objectMapper.writeValueAsString(userListMessage);
                Map<String, Channel> channels = groupChannels.get(groupId);
                if (channels != null) {
                    channels.values().forEach(channel -> 
                        channel.writeAndFlush(new TextWebSocketFrame(messageJson))
                    );
                }
                log.debug("Broadcasted online users for group {}: {}", groupId, users);
            }
        } catch (Exception e) {
            log.error("Error broadcasting online users for group " + groupId, e);
        }
    }

    // 用于存储Channel关联的用户信息
    private static class UserInfo {
        String userId;
        String groupId;

        UserInfo(String userId, String groupId) {
            this.userId = userId;
            this.groupId = groupId;
        }
    }
} 
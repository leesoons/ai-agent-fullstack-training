package com.leesoons.llmgateway.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建 Prompt 新版本的请求体。
 */
public class PromptCreate {

    @NotBlank(message = "id must not be blank")
    @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$", message = "invalid prompt id")
    private String id;

    @NotBlank(message = "name must not be blank")
    @Size(max = 200, message = "name too long")
    private String name;

    @NotBlank(message = "content must not be blank")
    private String content;

    private String description;
    private String role = "system";
    private boolean activate = true;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isActivate() {
        return activate;
    }

    public void setActivate(boolean activate) {
        this.activate = activate;
    }
}

package com.llmstudy.rag.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /extract/task 提交任务的响应
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MineruTaskSubmitResponse {

    private Integer code;
    private String msg;
    private TaskData data;

    public boolean isSuccess() {
        return code != null && code == 0;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public TaskData getData() {
        return data;
    }

    public void setData(TaskData data) {
        this.data = data;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskData {
        @JsonProperty("task_id")
        private String taskId;

        public String getTaskId() {
            return taskId;
        }

        public void setTaskId(String taskId) {
            this.taskId = taskId;
        }
    }
}

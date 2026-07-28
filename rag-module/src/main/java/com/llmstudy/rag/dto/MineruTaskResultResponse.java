package com.llmstudy.rag.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GET /extract/task/{taskId} 轮询任务结果的响应
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MineruTaskResultResponse {

    private Integer code;
    private String msg;
    private TaskData data;

    public boolean isFinished() {
        return data != null && "done".equals(data.getState());
    }

    public boolean isFailed() {
        return data != null && "failed".equals(data.getState());
    }

    public boolean isRunning() {
        return data != null && "running".equals(data.getState());
    }

    /**
     * 任务不存在：code 不为 0 且 data 为空或无状态
     */
    public boolean doesNotExist() {
        return code != null && code != 0 && (data == null || data.getState() == null);
    }

    public String getFullZipUrl() {
        return data == null ? null : data.getFullZipUrl();
    }

    public String getErrorMessage() {
        return data == null ? null : data.getErrMsg();
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
        private String state;
        @JsonProperty("full_zip_url")
        private String fullZipUrl;
        @JsonProperty("err_msg")
        private String errMsg;

        public String getTaskId() {
            return taskId;
        }

        public void setTaskId(String taskId) {
            this.taskId = taskId;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public String getFullZipUrl() {
            return fullZipUrl;
        }

        public void setFullZipUrl(String fullZipUrl) {
            this.fullZipUrl = fullZipUrl;
        }

        public String getErrMsg() {
            return errMsg;
        }

        public void setErrMsg(String errMsg) {
            this.errMsg = errMsg;
        }
    }
}

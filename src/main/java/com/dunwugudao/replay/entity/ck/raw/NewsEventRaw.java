package com.dunwugudao.replay.entity.ck.raw;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * news_event 政策事件原始行（S1 政策维度输入）。由 NewsEventMapper 读取。
 */
public class NewsEventRaw {

    private LocalDateTime eventTime;
    private String title;
    private String category;
    private BigDecimal sentimentScore;
    private Integer isPolicy;

    public LocalDateTime getEventTime() { return eventTime; }
    public void setEventTime(LocalDateTime eventTime) { this.eventTime = eventTime; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public BigDecimal getSentimentScore() { return sentimentScore; }
    public void setSentimentScore(BigDecimal sentimentScore) { this.sentimentScore = sentimentScore; }

    public Integer getIsPolicy() { return isPolicy; }
    public void setIsPolicy(Integer isPolicy) { this.isPolicy = isPolicy; }
}

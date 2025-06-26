// Task.java
package com.example.app;

// Using a simple POJO (Plain Old Java Object) for task data
public class Task {
    private String id;
    private String text;
    private boolean isFinished;

    public Task(String id, String text, boolean isFinished) {
        this.id = id;
        this.text = text;
        this.isFinished = isFinished;
    }

    // Getters
    public String getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public boolean isFinished() {
        return isFinished;
    }

    // Setters (if task properties can change, like isFinished)
    public void setText(String text) {
        this.text = text;
    }

    public void setFinished(boolean finished) {
        isFinished = finished;
    }
}

// TaskAdapter.java
package com.example.app;

import android.content.Context;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat; // Import for ContextCompat

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.TaskViewHolder> {

    private final Context context;
    private final List<Task> tasks;
    private final OnTaskClickListener listener;

    public interface OnTaskClickListener {
        void onTaskClick(int position);
    }

    public TaskAdapter(Context context, List<Task> tasks, OnTaskClickListener listener) {
        this.context = context;
        this.tasks = tasks;
        this.listener = listener;
    }

    @NonNull
    @Override
    public TaskViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.task_item, parent, false);
        return new TaskViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TaskViewHolder holder, int position) {
        Task task = tasks.get(position);
        holder.taskTextView.setText(task.getText());

        if (task.isFinished()) {
            holder.taskTextView.setPaintFlags(holder.taskTextView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            holder.taskTextView.setTextColor(ContextCompat.getColor(context, R.color.grey_text)); // Define this color
        } else {
            holder.taskTextView.setPaintFlags(holder.taskTextView.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
            holder.taskTextView.setTextColor(ContextCompat.getColor(context, R.color.dark_text)); // Define this color
        }

        holder.itemView.setOnClickListener(v -> listener.onTaskClick(position));
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }

    static class TaskViewHolder extends RecyclerView.ViewHolder {
        TextView taskTextView;

        public TaskViewHolder(@NonNull View itemView) {
            super(itemView);
            taskTextView = itemView.findViewById(R.id.taskText);
        }
    }
}

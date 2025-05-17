package com.amrithshagar.landrive;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.FileViewHolder> {

    private List<FileItem> fileList;

    public FileAdapter(List<FileItem> fileList) {
        this.fileList = fileList;
    }

    // ViewHolder inner class
    public static class FileViewHolder extends RecyclerView.ViewHolder {
        TextView textView;

        public FileViewHolder(@NonNull View itemView) {
            super(itemView);
            textView = itemView.findViewById(R.id.textViewFileName);
        }
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        FileItem fileItem = fileList.get(position);
        holder.textView.setText(fileItem.getFileName());
        // You can also bind other info here if you add more views in the item layout
    }

    @Override
    public int getItemCount() {
        return fileList.size();
    }
    public void updateFiles(List<FileItem> newFiles) {
        this.fileList = newFiles;
        notifyDataSetChanged();
    }
    public static class FileItem {
        String fileName;
        String filePath;
        long fileSize; // optional
        // add other fields if needed

        public FileItem(String fileName, String filePath) {
            this.fileName = fileName;
            this.filePath = filePath;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public String getFilePath() {
            return filePath;
        }

        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }

        public long getFileSize() {
            return fileSize;
        }

        public void setFileSize(long fileSize) {
            this.fileSize = fileSize;
        }

        // getters and setters
    }

}

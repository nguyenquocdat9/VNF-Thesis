package com.thesis.nfv.core;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.DefaultCategoryDataset;
import java.io.File;
import java.io.IOException;
import java.util.Map;

public class ChartExporter {
    public static void exportGroupedBarChart(String title, String xAxis, String yAxis,
                                             Map<Integer, Double> mshorData,
                                             Map<Integer, Double> greedyData,
                                             String fileName) {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();

        // Thêm dữ liệu vào dataset theo cặp (Value, Series, Category)
        // Category ở đây là các mức CPU (20, 40, 60, 80, 100)
        for (Integer cpu : mshorData.keySet()) {
            dataset.addValue(mshorData.get(cpu), "MSH-OR", cpu.toString());
            dataset.addValue(greedyData.get(cpu), "Greedy", cpu.toString());
        }

        // Tạo biểu đồ cột nhóm (Bar Chart)
        JFreeChart barChart = ChartFactory.createBarChart(
                title,
                xAxis,
                yAxis,
                dataset,
                PlotOrientation.VERTICAL,
                true, true, false);

        try {
            ChartUtils.saveChartAsPNG(new File(fileName), barChart, 800, 600);
            System.out.println("[INFO] Đã xuất biểu đồ cột nhóm: " + fileName);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
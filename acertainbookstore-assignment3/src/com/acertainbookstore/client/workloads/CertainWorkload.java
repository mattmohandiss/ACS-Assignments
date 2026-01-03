package com.acertainbookstore.client.workloads;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.acertainbookstore.business.CertainBookStore;
import com.acertainbookstore.business.StockBook;
import com.acertainbookstore.client.BookStoreHTTPProxy;
import com.acertainbookstore.client.StockManagerHTTPProxy;
import com.acertainbookstore.interfaces.BookStore;
import com.acertainbookstore.interfaces.StockManager;
import com.acertainbookstore.business.BookEditorPick;
import com.acertainbookstore.utils.BookStoreConstants;
import com.acertainbookstore.utils.BookStoreException;

public class CertainWorkload {

    /**
     * Simple class to hold the aggregated results for a single experiment run
     */
    static class ExperimentResult {
        int threads;
        double throughput;
        double latency;
        double goodput;
        int successfulInteractions;
        int totalInteractions;

        public String toCSV() {
            return String.format("%d,%.2f,%.2f,%.2f%%,%d,%d",
                threads, throughput, latency, goodput * 100, successfulInteractions, totalInteractions);
        }
    }

    /**
     * @param args
     */
    public static void main(String[] args) throws Exception {
        // The threads to loop through
        int[] clientCounts = {1, 2, 4, 8, 16, 32, 64};

        String serverAddress = "http://localhost:8081";
        boolean localTest = true;

        String localTestProperty = System.getProperty(BookStoreConstants.PROPERTY_KEY_LOCAL_TEST);
        localTest = (localTestProperty != null) ? Boolean.parseBoolean(localTestProperty) : localTest;

        BookStore bookStore = null;
        StockManager stockManager = null;

        if (localTest) {
            CertainBookStore store = new CertainBookStore();
            bookStore = store;
            stockManager = store;
        } else {
            stockManager = new StockManagerHTTPProxy(serverAddress + "/stock");
            bookStore = new BookStoreHTTPProxy(serverAddress);
        }

        List<ExperimentResult> allResults = new ArrayList<>();

        System.out.println("Starting Experiment (Type: " + (localTest ? "LOCAL" : "RPC") + ")");
        System.out.println("--------------------------------------------------");

        for (int numConcurrentWorkloadThreads : clientCounts) {
            List<WorkerRunResult> workerRunResults = new ArrayList<>();
            List<Future<WorkerRunResult>> runResults = new ArrayList<>();

            initializeBookStoreData(bookStore, stockManager);

            ExecutorService exec = Executors.newFixedThreadPool(numConcurrentWorkloadThreads);

            for (int i = 0; i < numConcurrentWorkloadThreads; i++) {
                WorkloadConfiguration config = new WorkloadConfiguration(bookStore, stockManager);
                Worker workerTask = new Worker(config);
                runResults.add(exec.submit(workerTask));
            }

            for (Future<WorkerRunResult> futureRunResult : runResults) {
                WorkerRunResult runResult = futureRunResult.get();
                workerRunResults.add(runResult);
            }

            exec.shutdownNow();

            // Aggregate metrics and print detailed block
            ExperimentResult result = processMetrics(workerRunResults, numConcurrentWorkloadThreads);
            allResults.add(result);
        }

        if (!localTest) {
            ((BookStoreHTTPProxy) bookStore).stop();
            ((StockManagerHTTPProxy) stockManager).stop();
        }

        // --- FINAL SUMMARY TABLE ---
        System.out.println("                            FINAL SUMMARY DATA                                  ");
        System.out.println("################################################################################");
        System.out.println("Threads,Throughput,Latency,Goodput,Successful_Cust_Interactions,Total_Interactions");
        for (ExperimentResult r : allResults) {
            System.out.println(r.toCSV());
        }
        System.out.println("################################################################################");
    }

    /**
     * Aggregates metrics, prints the detailed block, and returns an object for the summary table
     */
    public static ExperimentResult processMetrics(List<WorkerRunResult> workerRunResults, int numThreads) {
        long totalTimeInNanoSecs = 0;
        long totalRunTimeForLatency = 0;
        int totalSuccessfulFrequentInteraction = 0;
        int totalFrequentInteractions = 0;
        int totalAllInteractions = 0;

        for (WorkerRunResult result : workerRunResults) {
            totalTimeInNanoSecs += result.getElapsedTimeInNanoSecs();
            totalSuccessfulFrequentInteraction += result.getSuccessfulFrequentBookStoreInteractionRuns();
            totalFrequentInteractions += result.getTotalFrequentBookStoreInteractionRuns();
            totalAllInteractions += result.getTotalRuns();
            totalRunTimeForLatency += result.getElapsedTimeInNanoSecs();
        }

        double avgTimeInSeconds = (totalTimeInNanoSecs / (double) workerRunResults.size()) / 1_000_000_000.0;
        double throughput = (double) totalSuccessfulFrequentInteraction / avgTimeInSeconds;
        double avgLatencyInMs = (double) totalRunTimeForLatency / totalAllInteractions / 1_000_000.0;
        double goodputRatio = (totalFrequentInteractions > 0)
                ? (double) totalSuccessfulFrequentInteraction / totalFrequentInteractions
                : 0.0;

        // Print detailed block immediately for verification
        System.out.println("---------- Experimental Results (" + numThreads + " threads) ----------");
        System.out.println("Total Successful Customer Interactions: " + totalSuccessfulFrequentInteraction);
        System.out.println("Total Interactions (All Types): " + totalAllInteractions);
        System.out.println("Throughput (Customer Interactions/sec): " + String.format("%.2f", throughput));
        System.out.println("Average Latency (ms/interaction): " + String.format("%.2f", avgLatencyInMs));
        System.out.println("Customer Goodput Ratio: " + String.format("%.2f%%", goodputRatio * 100));
        System.out.println("----------------------------------------------------------");

        // Save result
        ExperimentResult res = new ExperimentResult();
        res.threads = numThreads;
        res.throughput = throughput;
        res.latency = avgLatencyInMs;
        res.goodput = goodputRatio;
        res.successfulInteractions = totalSuccessfulFrequentInteraction;
        res.totalInteractions = totalAllInteractions;
        return res;
    }

    public static void initializeBookStoreData(BookStore bookStore, StockManager stockManager) throws BookStoreException {
        stockManager.removeAllBooks();
        int numInitBooks = 500;
        BookSetGenerator generator = new BookSetGenerator();
        Set<StockBook> books = generator.nextSetOfStockBooks(numInitBooks);
        stockManager.addBooks(books);

        Set<BookEditorPick> editorPicks = new HashSet<>();
        List<StockBook> bookList = new ArrayList<>(books);
        for (int i = 0; i < bookList.size(); i++) {
            if (i % 2 == 0) {
                editorPicks.add(new BookEditorPick(bookList.get(i).getISBN(), true));
            }
        }
        if (!editorPicks.isEmpty()) {
            stockManager.updateEditorPicks(editorPicks);
        }
    }
}
/**
 *
 */
package com.acertainbookstore.client.workloads;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import com.acertainbookstore.business.Book;
import com.acertainbookstore.business.BookCopy;
import com.acertainbookstore.business.StockBook;
import com.acertainbookstore.utils.BookStoreException;

/**
 *
 * Worker represents the workload runner which runs the workloads with
 * parameters using WorkloadConfiguration and then reports the results
 *
 */
public class Worker implements Callable<WorkerRunResult> {
    private WorkloadConfiguration configuration = null;
    private int numSuccessfulFrequentBookStoreInteraction = 0;
    private int numTotalFrequentBookStoreInteraction = 0;

    public Worker(WorkloadConfiguration config) {
	configuration = config;
    }

    /**
     * Run the appropriate interaction while trying to maintain the configured
     * distributions
     *
     * Updates the counts of total runs and successful runs for customer
     * interaction
     *
     * @param chooseInteraction
     * @return
     */
    private boolean runInteraction(float chooseInteraction) {
	try {
	    float percentRareStockManagerInteraction = configuration.getPercentRareStockManagerInteraction();
	    float percentFrequentStockManagerInteraction = configuration.getPercentFrequentStockManagerInteraction();

	    if (chooseInteraction < percentRareStockManagerInteraction) {
		runRareStockManagerInteraction();
	    } else if (chooseInteraction < percentRareStockManagerInteraction
		    + percentFrequentStockManagerInteraction) {
		runFrequentStockManagerInteraction();
	    } else {
		numTotalFrequentBookStoreInteraction++;
		runFrequentBookStoreInteraction();
		numSuccessfulFrequentBookStoreInteraction++;
	    }
	} catch (BookStoreException ex) {
	    return false;
	}
	return true;
    }

    /**
     * Run the workloads trying to respect the distributions of the interactions
     * and return result in the end
     */
    public WorkerRunResult call() throws Exception {
	int count = 1;
	long startTimeInNanoSecs = 0;
	long endTimeInNanoSecs = 0;
	int successfulInteractions = 0;
	long timeForRunsInNanoSecs = 0;

	Random rand = new Random();
	float chooseInteraction;

	// Perform the warmup runs
	while (count++ <= configuration.getWarmUpRuns()) {
	    chooseInteraction = rand.nextFloat() * 100f;
	    runInteraction(chooseInteraction);
	}

	count = 1;
	numTotalFrequentBookStoreInteraction = 0;
	numSuccessfulFrequentBookStoreInteraction = 0;

	// Perform the actual runs
	startTimeInNanoSecs = System.nanoTime();
	while (count++ <= configuration.getNumActualRuns()) {
	    chooseInteraction = rand.nextFloat() * 100f;
	    if (runInteraction(chooseInteraction)) {
		successfulInteractions++;
	    }
	}
	endTimeInNanoSecs = System.nanoTime();
	timeForRunsInNanoSecs += (endTimeInNanoSecs - startTimeInNanoSecs);
	return new WorkerRunResult(successfulInteractions, timeForRunsInNanoSecs, configuration.getNumActualRuns(),
		numSuccessfulFrequentBookStoreInteraction, numTotalFrequentBookStoreInteraction);
    }

/**
     * Runs the new stock acquisition interaction
     * * @throws BookStoreException
     */
    private void runRareStockManagerInteraction() throws BookStoreException {
        // 1. Get all books currently in the store
        List<StockBook> currentBooks = configuration.getStockManager().getBooks();
        Set<Integer> currentISBNs = currentBooks.stream()
                .map(StockBook::getISBN)
                .collect(Collectors.toSet());

        // 2. Generate random candidate books
        Set<StockBook> candidates = configuration.getBookSetGenerator().nextSetOfStockBooks(configuration.getNumBooksToAdd());

        // 3. Filter out books that already exist (we only add new ISBNs)
        Set<StockBook> booksToAdd = new HashSet<>();
        for (StockBook book : candidates) {
            if (!currentISBNs.contains(book.getISBN())) {
                booksToAdd.add(book);
            }
        }

        // 4. Add the new books to the store
        if (!booksToAdd.isEmpty()) {
            configuration.getStockManager().addBooks(booksToAdd);
        }
    }

    /**
     * Runs the stock replenishment interaction
     * * @throws BookStoreException
     */
    private void runFrequentStockManagerInteraction() throws BookStoreException {
        // 1. Get all current books
        List<StockBook> currentBooks = configuration.getStockManager().getBooks();

        // 2. Sort by number of copies (ascending) to find those with least stock
        List<StockBook> sortedBooks = new ArrayList<>(currentBooks);
        Collections.sort(sortedBooks, Comparator.comparingInt(StockBook::getNumCopies));

        // 3. Select k books with smallest quantities
        int k = configuration.getNumBooksWithLeastCopies();
        List<StockBook> booksToReplenish = sortedBooks.subList(0, Math.min(k, sortedBooks.size()));

        // 4. Create Set<BookCopy> to add copies
        Set<BookCopy> copiesToAdd = new HashSet<>();
        for (StockBook book : booksToReplenish) {
            copiesToAdd.add(new BookCopy(book.getISBN(), configuration.getNumAddCopies()));
        }

        // 5. Execute addCopies
        if (!copiesToAdd.isEmpty()) {
            configuration.getStockManager().addCopies(copiesToAdd);
        }
    }

    /**
     * Runs the customer interaction
     * * @throws BookStoreException
     */
    private void runFrequentBookStoreInteraction() throws BookStoreException {
        // 1. Get Editor Picks
        List<Book> editorPicks = configuration.getBookStore().getEditorPicks(configuration.getNumEditorPicksToGet());

        if (editorPicks.isEmpty()) {
            return;
        }

        Set<Integer> pickISBNs = editorPicks.stream()
                .map(Book::getISBN)
                .collect(Collectors.toSet());

        // 2. Sample a random subset of these ISBNs to buy
        Set<Integer> isbnsToBuy = configuration.getBookSetGenerator()
                .sampleFromSetOfISBNs(pickISBNs, configuration.getNumBooksToBuy());

        // 3. Create BookCopy set for the purchase
        Set<BookCopy> booksToBuy = new HashSet<>();
        for (Integer isbn : isbnsToBuy) {
            booksToBuy.add(new BookCopy(isbn, configuration.getNumBookCopiesToBuy()));
        }

        // 4. Execute buyBooks
        if (!booksToBuy.isEmpty()) {
            configuration.getBookStore().buyBooks(booksToBuy);
        }
    }
}

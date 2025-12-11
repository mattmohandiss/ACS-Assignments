package com.acertainbookstore.client.tests;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Set;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.acertainbookstore.business.BookCopy;
import com.acertainbookstore.business.ImmutableStockBook;
import com.acertainbookstore.business.SingleLockConcurrentCertainBookStore;
import com.acertainbookstore.business.StockBook;
import com.acertainbookstore.business.TwoLevelLockingConcurrentCertainBookStore;
import com.acertainbookstore.interfaces.BookStore;
import com.acertainbookstore.interfaces.StockManager;
import com.acertainbookstore.utils.BookStoreConstants;
import com.acertainbookstore.utils.BookStoreException;

import java.util.List;

public class DisjointWritersTest {
    private static boolean singleLock = true;
    private static StockManager storeManager;
    private static BookStore client;

    @BeforeClass
    public static void setUpBeforeClass() {
        try {
            String singleLockProperty = System.getProperty(BookStoreConstants.PROPERTY_KEY_SINGLE_LOCK);
            singleLock = (singleLockProperty != null) ? Boolean.parseBoolean(singleLockProperty) : singleLock;

            if (singleLock) {
                SingleLockConcurrentCertainBookStore store = new SingleLockConcurrentCertainBookStore();
                storeManager = store;
                client = store;
            } else {
                TwoLevelLockingConcurrentCertainBookStore store = new TwoLevelLockingConcurrentCertainBookStore();
                storeManager = store;
                client = store;
            }

            storeManager.removeAllBooks();
        } catch (Exception e) {
            e.printStackTrace();
            fail("failed to set up test");
        }
    }

    @Before
    public void clearStoreBeforeEach() throws BookStoreException {
        storeManager.removeAllBooks();
    }

    @After
    public void clearStoreAfterEach() throws BookStoreException {
        storeManager.removeAllBooks();
    }

    @AfterClass
    public static void tearDownAfterClass() throws BookStoreException {
        storeManager.removeAllBooks();
    }

    private void addBooks(int[] isbns, int initialCopies) throws BookStoreException {
        Set<StockBook> booksToAdd = new HashSet<StockBook>();
        for (int i = 0; i < isbns.length; i++) {
            booksToAdd.add(new ImmutableStockBook(
                    isbns[i],
                    "Disjoint Book " + i,
                    "Test Author",
                    10.0f,
                    initialCopies,
                    0, 0, 0,
                    false));
        }
        storeManager.addBooks(booksToAdd);
    }

    private Set<BookCopy> oneCopyOfEach(int[] isbns) {
        Set<BookCopy> copies = new HashSet<BookCopy>();
        for (int i = 0; i < isbns.length; i++) {
            copies.add(new BookCopy(isbns[i], 1));
        }
        return copies;
    }

    private List<StockBook> getStockFor(int[] isbns) throws BookStoreException {
        Set<Integer> isbnSet = new HashSet<Integer>();
        for (int i = 0; i < isbns.length; i++) {
            isbnSet.add(isbns[i]);
        }
        return storeManager.getBooksByISBN(isbnSet);
    }

    @Test
    public void testDisjointWritersNoInterference() throws Exception {
        final int threads = 8;
        final int booksPerThread = 3;
        final int initialCopies = 100;
        final int iterations = 200;

        // prepare disjoint ISBN ranges
        final int[] allIsbns = new int[threads * booksPerThread];
        for (int t = 0; t < threads; t++) {
            for (int b = 0; b < booksPerThread; b++) {
                allIsbns[t * booksPerThread + b] = 3000 + t * 100 + b;
            }
        }

        addBooks(allIsbns, initialCopies);

        final boolean[] failed = new boolean[] { false };
        final String[] errorMsg = new String[] { null };

        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int offset = t * booksPerThread;
            ts[t] = new Thread(new Runnable() {
                public void run() {
                    try {
                        int[] myIsbns = new int[booksPerThread];
                        for (int i = 0; i < booksPerThread; i++) myIsbns[i] = allIsbns[offset + i];
                        Set<BookCopy> oneOfEach = oneCopyOfEach(myIsbns);
                        for (int it = 0; it < iterations; it++) {
                            client.buyBooks(oneOfEach);
                            storeManager.addCopies(oneOfEach);
                        }
                    } catch (BookStoreException e) {
                        failed[0] = true;
                        errorMsg[0] = "Writer thread failed: " + e.getMessage();
                    }
                }
            }, "writer-" + t);
            ts[t].start();
        }

        for (int t = 0; t < threads; t++) ts[t].join();

        if (failed[0]) fail(errorMsg[0]);

        List<StockBook> finalStock = getStockFor(allIsbns);
        assertEquals(allIsbns.length, finalStock.size());
        for (StockBook book : finalStock) {
            assertEquals("Final copies mismatch for ISBN " + book.getISBN(), initialCopies, book.getNumCopies());
        }
    }
}

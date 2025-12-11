package com.acertainbookstore.client.tests;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Random;
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

public class MixedReadersWritersTest {
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
                    "Mixed Book " + i,
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
    public void testMixedReadersWritersStress() throws Exception {
        final int numBooks = 10;
        final int initialCopies = 50;
        final int readerThreads = 6;
        final int writerThreads = 4;
        final int readerSnapshots = 2000;
        final int writerIterations = 1000;

        final int[] isbns = new int[numBooks];
        for (int i = 0; i < numBooks; i++) isbns[i] = 5000 + i;

        addBooks(isbns, initialCopies);

        final boolean[] failed = new boolean[] { false };
        final String[] errorMsg = new String[] { null };
        final boolean[] running = new boolean[] { true };

        Random rand = new Random();

        Runnable writer = new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < writerIterations && running[0]; i++) {
                        // pick random small subset
                        int a = rand.nextInt(numBooks);
                        int b = rand.nextInt(numBooks);
                        int[] picks = new int[] { isbns[a], isbns[b] };
                        Set<BookCopy> toBuy = oneCopyOfEach(picks);
                        client.buyBooks(toBuy);
                        storeManager.addCopies(toBuy);
                    }
                } catch (BookStoreException e) {
                    failed[0] = true;
                    errorMsg[0] = "writer failed: " + e.getMessage();
                }
            }
        };

        Runnable reader = new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < readerSnapshots && !failed[0]; i++) {
                        // pick random distinct indices
                        Set<Integer> picksSet = new HashSet<Integer>();
                        while (picksSet.size() < 3) {
                            picksSet.add(rand.nextInt(numBooks));
                        }
                        int[] picks = picksSet.stream().mapToInt(Integer::intValue).map(idx -> isbns[idx]).toArray();
                        List<StockBook> snapshot = getStockFor(picks);
                        if (snapshot.size() != picks.length) {
                            failed[0] = true;
                            errorMsg[0] = "unexpected size: " + snapshot.size();
                            return;
                        }
                        for (StockBook book : snapshot) {
                            int copies = book.getNumCopies();
                            if (copies < 0) {
                                failed[0] = true;
                                errorMsg[0] = "invalid copies: " + copies;
                                return;
                            }
                        }
                    }
                } catch (BookStoreException e) {
                    failed[0] = true;
                    errorMsg[0] = "reader failed: " + e.getMessage();
                } finally {
                    running[0] = false;
                }
            }
        };

        Thread[] wts = new Thread[writerThreads];
        Thread[] rts = new Thread[readerThreads];

        for (int i = 0; i < writerThreads; i++) {
            wts[i] = new Thread(writer, "writer-" + i);
            wts[i].start();
        }
        for (int i = 0; i < readerThreads; i++) {
            rts[i] = new Thread(reader, "reader-" + i);
            rts[i].start();
        }

        for (int i = 0; i < readerThreads; i++) rts[i].join();
        running[0] = false;
        for (int i = 0; i < writerThreads; i++) wts[i].join();

        if (failed[0]) fail(errorMsg[0]);

        List<StockBook> finalStock = getStockFor(isbns);
        assertEquals(numBooks, finalStock.size());
    }
}

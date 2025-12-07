package com.acertainbookstore.client.tests;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
import com.acertainbookstore.client.BookStoreHTTPProxy;
import com.acertainbookstore.client.StockManagerHTTPProxy;
import com.acertainbookstore.interfaces.BookStore;
import com.acertainbookstore.interfaces.StockManager;
import com.acertainbookstore.utils.BookStoreConstants;
import com.acertainbookstore.utils.BookStoreException;

/**
 * Concurrency tests for Programming Assignment 2.
 *
 * Implements:
 *  Test 1: buyBooks vs addCopies on same set S, fixed number of operations.
 *  Test 2: writer alternates buy + replenish, reader checks snapshot consistency.
 */
public class ConcurrentBookStoreTest {

	/** The local test. */
	private static boolean localTest = true;

	/** Single lock test flag */
	private static boolean singleLock = true;

	/** The store manager. */
	private static StockManager storeManager;

	/** The client. */
	private static BookStore client;

	// ----------- Test parameters (overridable with system properties) -----------

	/**
	 * Number of operations each thread should perform in Test 1.
	 * Override with: -Dconcurrencytest.ops=2000
	 */
	private static final int DEFAULT_TEST1_OPS = 1000;

	/**
	 * Number of reader snapshots to check in Test 2.
	 * Override with: -Dconcurrencytest.snapshots=50000
	 */
	private static final int DEFAULT_TEST2_SNAPSHOTS = 20000;

	/**
	 * How many buy+replenish cycles the writer attempts (upper bound).
	 * Override with: -Dconcurrencytest.cycles=5000
	 */
	private static final int DEFAULT_TEST2_CYCLES = 5000;

	private static int getIntProperty(String key, int defaultVal) {
		String p = System.getProperty(key);
		if (p == null) return defaultVal;
		try {
			int v = Integer.parseInt(p);
			return (v > 0) ? v : defaultVal;
		} catch (NumberFormatException ex) {
			return defaultVal;
		}
	}

	@BeforeClass
	public static void setUpBeforeClass() {
		try {
			String localTestProperty = System.getProperty(BookStoreConstants.PROPERTY_KEY_LOCAL_TEST);
			localTest = (localTestProperty != null) ? Boolean.parseBoolean(localTestProperty) : localTest;

			String singleLockProperty = System.getProperty(BookStoreConstants.PROPERTY_KEY_SINGLE_LOCK);
			singleLock = (singleLockProperty != null) ? Boolean.parseBoolean(singleLockProperty) : singleLock;

			if (localTest) {
				if (singleLock) {
					SingleLockConcurrentCertainBookStore store = new SingleLockConcurrentCertainBookStore();
					storeManager = store;
					client = store;
				} else {
					TwoLevelLockingConcurrentCertainBookStore store = new TwoLevelLockingConcurrentCertainBookStore();
					storeManager = store;
					client = store;
				}
			} else {
				storeManager = new StockManagerHTTPProxy("http://localhost:8081/stock");
				client = new BookStoreHTTPProxy("http://localhost:8081");
			}

			storeManager.removeAllBooks();
		} catch (Exception e) {
			e.printStackTrace();
			fail("Failed to set up test environment: " + e.getMessage());
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

		if (!localTest) {
			((BookStoreHTTPProxy) client).stop();
			((StockManagerHTTPProxy) storeManager).stop();
		}
	}

	// ---------------------------- Helpers ----------------------------

	private void addBooksToStore(Set<StockBook> books) throws BookStoreException {
		storeManager.addBooks(books);
	}

	private Set<StockBook> makeBookSet(int[] isbns, String titlePrefix, int initialCopies) {
		Set<StockBook> books = new HashSet<>();
		int idx = 0;
		for (int isbn : isbns) {
			books.add(new ImmutableStockBook(
					isbn,
					titlePrefix + " " + idx,
					"Concurrent Author",
					10.0f,
					initialCopies,
					0, 0, 0,
					false
			));
			idx++;
		}
		return books;
	}

	private Set<BookCopy> makeOneCopyEach(int[] isbns) {
		Set<BookCopy> copies = new HashSet<>();
		for (int isbn : isbns) {
			copies.add(new BookCopy(isbn, 1));
		}
		return copies;
	}

	private Set<BookCopy> makeNCopiesEach(int[] isbns, int n) {
		Set<BookCopy> copies = new HashSet<>();
		for (int isbn : isbns) {
			copies.add(new BookCopy(isbn, n));
		}
		return copies;
	}

	private List<StockBook> getStockByISBN(int[] isbns) throws BookStoreException {
		Set<Integer> set = new HashSet<>();
		for (int isbn : isbns) set.add(isbn);
		return storeManager.getBooksByISBN(set);
	}

	private void assertAllCopiesEqual(int[] isbns, int expectedCopies) throws BookStoreException {
		List<StockBook> books = getStockByISBN(isbns);
		assertEquals("Unexpected number of books in store", isbns.length, books.size());
		for (StockBook b : books) {
			assertEquals("Wrong final copies for ISBN " + b.getISBN(), expectedCopies, b.getNumCopies());
		}
	}

	// ---------------------------- Test 1 ----------------------------
	/**
	 * Test 1:
	 * Two clients C1 and C2, running in different threads, each invoke a fixed number
	 * of operations against the BookStore and StockManager interfaces.
	 * Both operate against the same set of books S.
	 *
	 * C1 calls buyBooks, while C2 calls addCopies on S.
	 *
	 * The initial state should have a sufficient number of copies.
	 * In the end, S should end with the same number of copies in stock as they started.
	 */
	@Test
	public void testConcurrentBuyAndAddCopiesFixedOps() throws Exception {
		final int ops = getIntProperty("concurrencytest.ops", DEFAULT_TEST1_OPS);

		// Shared set S
		final int[] isbns = new int[] { 1001, 1002, 1003 };

		// Make initial large enough for all buys to succeed even without help.
		final int initialCopies = ops + 10;

		addBooksToStore(makeBookSet(isbns, "Test1 Book", initialCopies));

		final Set<BookCopy> oneCopyEach = makeOneCopyEach(isbns);

		final CountDownLatch startGate = new CountDownLatch(1);
		final CountDownLatch doneGate = new CountDownLatch(2);
		final AtomicReference<Throwable> threadFailure = new AtomicReference<>(null);

		Runnable buyer = () -> {
			try {
				startGate.await();
				for (int i = 0; i < ops; i++) {
					client.buyBooks(oneCopyEach);
				}
			} catch (Throwable t) {
				threadFailure.compareAndSet(null, t);
			} finally {
				doneGate.countDown();
			}
		};

		Runnable adder = () -> {
			try {
				startGate.await();
				for (int i = 0; i < ops; i++) {
					storeManager.addCopies(oneCopyEach);
				}
			} catch (Throwable t) {
				threadFailure.compareAndSet(null, t);
			} finally {
				doneGate.countDown();
			}
		};

		Thread t1 = new Thread(buyer, "Test1-Buyer");
		Thread t2 = new Thread(adder, "Test1-Adder");

		t1.start();
		t2.start();

		startGate.countDown();

		boolean finished = doneGate.await(60, TimeUnit.SECONDS);
		assertTrue("Test 1 threads did not finish in time", finished);

		if (threadFailure.get() != null) {
			fail("Thread failure in Test 1: " + threadFailure.get());
		}

		// Final stock should match the initial stock per book.
		assertAllCopiesEqual(isbns, initialCopies);
	}

	// ---------------------------- Test 2 ----------------------------
	/**
	 * Test 2:
	 * Two clients C1 and C2, running in different threads, continuously invoke operations.
	 *
	 * C1:
	 *  - buy a given fixed collection of books (e.g., trilogy)
	 *  - then addCopies to replenish the stock of the bought books
	 *
	 * C2:
	 *  - continuously reads snapshots (we use getBooksByISBN for quantities)
	 *  - ensures that each snapshot is consistent:
	 *      Either all books look like "just bought" OR all look like "just replenished"
	 *
	 * Test fails immediately if an inconsistent snapshot is observed.
	 * Succeeds after a large number of consistent reads.
	 */
	@Test
	public void testConsistentSnapshotsDuringBuyAndReplenish() throws Exception {
		final int snapshotsToCheck = getIntProperty("concurrencytest.snapshots", DEFAULT_TEST2_SNAPSHOTS);
		final int writerCyclesMax = getIntProperty("concurrencytest.cycles", DEFAULT_TEST2_CYCLES);

		// Fixed collection (e.g., trilogy)
		final int[] isbns = new int[] { 2001, 2002, 2003 };

		// Choose a simple two-state model:
		// baseCopies: "replenished" state
		// baseCopies - 1: "just bought" state
		final int baseCopies = 10;

		addBooksToStore(makeBookSet(isbns, "Test2 Book", baseCopies));

		final Set<BookCopy> oneCopyEach = makeOneCopyEach(isbns);

		final CountDownLatch startGate = new CountDownLatch(1);
		final AtomicBoolean keepRunning = new AtomicBoolean(true);
		final AtomicReference<Throwable> threadFailure = new AtomicReference<>(null);

		Runnable writer = () -> {
			try {
				startGate.await();

				int cycles = 0;
				while (keepRunning.get() && cycles < writerCyclesMax) {
					// Buy 1 of each
					client.buyBooks(oneCopyEach);
					// Replenish 1 of each
					storeManager.addCopies(oneCopyEach);
					cycles++;
				}
			} catch (Throwable t) {
				threadFailure.compareAndSet(null, t);
			}
		};

		Runnable reader = () -> {
			try {
				startGate.await();

				for (int i = 0; i < snapshotsToCheck; i++) {
					List<StockBook> snapshot = getStockByISBN(isbns);

					// We expect exactly 3 books
					if (snapshot.size() != isbns.length) {
						throw new AssertionError("Unexpected snapshot size: " + snapshot.size());
					}

					// Capture the observed copies for the trilogy in this snapshot
					int[] copies = new int[isbns.length];
					for (int j = 0; j < snapshot.size(); j++) {
						copies[j] = snapshot.get(j).getNumCopies();
					}

					// The snapshot is consistent if:
					//   - all are baseCopies
					//   - OR all are baseCopies - 1
					boolean allBase = true;
					boolean allBought = true;

					for (int c : copies) {
						if (c != baseCopies) allBase = false;
						if (c != baseCopies - 1) allBought = false;
					}

					if (!(allBase || allBought)) {
						throw new AssertionError(
								"Inconsistent snapshot observed: " + Arrays.toString(copies)
								+ " expected all " + baseCopies + " or all " + (baseCopies - 1)
						);
					}
				}
			} catch (Throwable t) {
				threadFailure.compareAndSet(null, t);
			} finally {
				keepRunning.set(false);
			}
		};

		Thread tWriter = new Thread(writer, "Test2-Writer");
		Thread tReader = new Thread(reader, "Test2-Reader");

		tWriter.start();
		tReader.start();

		startGate.countDown();

		// Join with timeouts to avoid hanging tests
		tReader.join(TimeUnit.SECONDS.toMillis(60));
		keepRunning.set(false);
		tWriter.join(TimeUnit.SECONDS.toMillis(60));

		if (threadFailure.get() != null) {
			fail("Thread failure in Test 2: " + threadFailure.get());
		}

		// If we got here, we observed enough consistent snapshots.
		assertTrue(true);
	}
}

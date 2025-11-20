package com.acertainbookstore.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;

import com.acertainbookstore.client.BookStoreClientConstants;
import com.acertainbookstore.interfaces.BookStoreSerializer;

/**
 * {@link BookStoreUtility} implements utility methods used by the bookstore
 * server and client.
 */
public final class BookStoreUtility {

	/** Prevent instantiation. */
	private BookStoreUtility() {}

	/* ------------------------- static validation helpers ------------------------- */

	public static boolean isInvalidISBN(int isbn) {
		return isbn < 1;
	}

	public static boolean isInvalidRating(int rating) {
		return rating < 0 || rating > 5;
	}

	public static boolean isInvalidNoCopies(int copies) {
		return copies < 1;
	}

	public static boolean isEmpty(String str) {
		return str == null || str.isEmpty();
	}

	public static float convertStringToFloat(String str, float failureSignal) {
		float returnValue = failureSignal;
		try {
			returnValue = Float.parseFloat(str);
		} catch (NumberFormatException | NullPointerException ex) {
			System.err.println(ex.getMessage());
		}
		return returnValue;
	}

	public static int convertStringToInt(String str) throws BookStoreException {
		try {
			return Integer.parseInt(str);
		} catch (Exception ex) {
			throw new BookStoreException(ex);
		}
	}

	public static BookStoreMessageTag convertURItoMessageTag(String requestURI) {
		try {
			return BookStoreMessageTag.valueOf(requestURI.substring(1).toUpperCase());
		} catch (IllegalArgumentException | NullPointerException ex) {
			System.err.println(ex.getMessage());
			return null;
		}
	}

	/* --------------------------- HTTP helper method --------------------------- */

	/**
	 * Sends the request described by {@code bookStoreRequest} using Jetty's
	 * {@link HttpClient}, deserialises the XML payload with the supplied
	 * {@link BookStoreSerializer}, and converts server‑side exceptions into
	 * client‑side {@link BookStoreException}s.
	 */
	public static BookStoreResponse performHttpExchange(HttpClient client,
														BookStoreRequest bookStoreRequest,
														BookStoreSerializer serializer)
			throws BookStoreException {

		/* -------- build the outbound HTTP request -------- */
		Request request;
		switch (bookStoreRequest.getMethod()) {
			case GET:
				request = client.newRequest(bookStoreRequest.getURLString()).method(HttpMethod.GET);
				break;
			case POST:
				try {
					byte[] bytes = serializer.serialize(bookStoreRequest.getInputValue());
					ContentProvider cp = new BytesContentProvider(bytes);
					request = client.POST(bookStoreRequest.getURLString()).content(cp);
				} catch (IOException ex) {
					throw new BookStoreException("Serialization error", ex);
				}
				break;
			default:
				throw new IllegalArgumentException("HTTP Method not supported.");
		}

		/* -------- send the request and obtain the response -------- */
		ContentResponse response;
		try {
			response = request.send();
		} catch (InterruptedException ex) {
			throw new BookStoreException(BookStoreClientConstants.STR_ERR_CLIENT_REQUEST_SENDING, ex);
		} catch (TimeoutException ex) {
			throw new BookStoreException(BookStoreClientConstants.STR_ERR_CLIENT_REQUEST_TIMEOUT, ex);
		} catch (ExecutionException ex) {
			throw new BookStoreException(BookStoreClientConstants.STR_ERR_CLIENT_REQUEST_EXCEPTION, ex);
		}

		/* -------- validate response before deserialising -------- */
		int status = response.getStatus();
		if (status >= 400) {
			String body = new String(response.getContent(), StandardCharsets.UTF_8);
			throw new BookStoreException("Remote server returned HTTP " + status + ":\n" + body);
		}

		String contentType = response.getHeaders().get(HttpHeader.CONTENT_TYPE);
		if (contentType != null && contentType.contains("text/html")) {
			String body = new String(response.getContent(), StandardCharsets.UTF_8);
			throw new BookStoreException("Expected XML but received HTML:\n" + body);
		}

		/* -------- safe deserialisation of the (now validated) XML payload -------- */
		BookStoreResponse bookStoreResponse;
		try {
			bookStoreResponse = (BookStoreResponse) serializer.deserialize(response.getContent());
		} catch (IOException ex) {
			throw new BookStoreException("Deserialization error", ex);
		}

		/* -------- propagate any server‑side exceptions back to the caller -------- */
		BookStoreException exception = bookStoreResponse.getException();
		if (exception != null) {
			throw exception;
		}

		return bookStoreResponse;
	}
}

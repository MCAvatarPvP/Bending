package com.projectkorra.projectkorra.storage;

/**
 * Runtime exception to wrap lower level storage specific failures.
 */
public class StorageException extends RuntimeException {

	public StorageException(final String message) {
		super(message);
	}

	public StorageException(final String message, final Throwable cause) {
		super(message, cause);
	}
}


package com.projectkorra.projectkorra.platform;

/** Scheduler lambda target with a compiler-provided capture description.
 * No Java object serialization is used by gameplay or rollback transfer.
 */
@FunctionalInterface
public interface PKRunnable extends Runnable, java.io.Serializable { }

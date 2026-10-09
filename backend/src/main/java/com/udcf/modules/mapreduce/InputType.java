package com.udcf.modules.mapreduce;

/**
 * Where a run's input comes from.
 *
 * <p>No dedicated test: a plain enum; each value is tested through RunInputLoaderTest and
 * MapReduceModuleTest.</p>
 */
public enum InputType {
    /** The bundled sample text. */
    SAMPLE,
    /** A .txt file uploaded with the run request, held in memory only. */
    UPLOAD,
    /** The live cluster event log, snapshotted once when the run starts (link L5). */
    EVENT_LOG
}

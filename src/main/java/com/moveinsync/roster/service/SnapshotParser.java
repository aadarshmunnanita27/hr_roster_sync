package com.moveinsync.roster.service;

import java.io.Reader;
import java.io.IOException;
import org.apache.commons.csv.CSVParser;

public interface SnapshotParser {
    CSVParser parse(Reader reader) throws IOException;
}

package com.moveinsync.roster.service;

import java.io.Reader;
import java.io.IOException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.stereotype.Component;

@Component
public class CsvSnapshotParser implements SnapshotParser {
    @Override
    public CSVParser parse(Reader reader) throws IOException {
        return CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .get()
                .parse(reader);
    }
}

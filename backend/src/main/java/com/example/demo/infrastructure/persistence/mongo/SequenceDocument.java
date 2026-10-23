package com.example.demo.infrastructure.persistence.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "sequences")
public class SequenceDocument {
    @Id
    private String id;
    private long value;

    public SequenceDocument() { }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public long getValue() { return value; }
    public void setValue(long value) { this.value = value; }
}

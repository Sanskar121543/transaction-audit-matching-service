package io.github.sanskar121543.reconciliation.breaks;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A note on a break. Append-only: the database refuses to change or delete one. */
@Entity
@Table(name = "break_notes")
public class BreakNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "break_id")
    private long breakId;

    private String author;

    private String body;

    protected BreakNote() {
    }

    public BreakNote(long breakId, String author, String body) {
        this.breakId = breakId;
        this.author = author;
        this.body = body;
    }

    public Long getId() {
        return id;
    }
}

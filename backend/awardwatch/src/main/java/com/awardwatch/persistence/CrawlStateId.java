package com.awardwatch.persistence;

import com.awardwatch.domain.Program;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Embeddable
public class CrawlStateId implements Serializable {

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String origin;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Program program;

    @Column(name = "date_from", nullable = false)
    private LocalDate dateFrom;

    @Column(name = "date_to", nullable = false)
    private LocalDate dateTo;

    protected CrawlStateId() {
    }

    public CrawlStateId(
        String origin,
        String destination,
        Program program,
        LocalDate dateFrom,
        LocalDate dateTo
    ) {
        this.origin = Objects.requireNonNull(origin, "origin cannot be null");
        this.destination = Objects.requireNonNull(destination, "destination cannot be null");
        this.program = Objects.requireNonNull(program, "program cannot be null");
        this.dateFrom = Objects.requireNonNull(dateFrom, "dateFrom cannot be null");
        this.dateTo = Objects.requireNonNull(dateTo, "dateTo cannot be null");
    }

    public String getOrigin() {
        return origin;
    }

    public String getDestination() {
        return destination;
    }

    public Program getProgram() {
        return program;
    }

    public LocalDate getDateFrom() {
        return dateFrom;
    }

    public LocalDate getDateTo() {
        return dateTo;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CrawlStateId that)) {
            return false;
        }
        return Objects.equals(origin, that.origin)
            && Objects.equals(destination, that.destination)
            && program == that.program
            && Objects.equals(dateFrom, that.dateFrom)
            && Objects.equals(dateTo, that.dateTo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(origin, destination, program, dateFrom, dateTo);
    }
}

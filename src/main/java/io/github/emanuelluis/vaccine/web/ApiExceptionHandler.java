package io.github.emanuelluis.vaccine.web;

import java.sql.SQLException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain errors to RFC 9457 problem details. Bean-validation failures on request parameters
 * are already answered with 400 problem details by Spring Boot ({@code spring.mvc.problemdetails}).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Postgres undefined_table and invalid_schema_name: dbt has not built the marts yet. */
    private static final Set<String> MISSING_RELATION_STATES = Set.of("42P01", "3F000");

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalidRequest(InvalidRequestException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(BadSqlGrammarException.class)
    ProblemDetail badSql(BadSqlGrammarException ex) {
        SQLException cause = ex.getSQLException();
        if (cause != null && MISSING_RELATION_STATES.contains(cause.getSQLState())) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "The warehouse marts are not built yet: run `make demo` (or `make dbt-build`).");
            problem.setTitle("Marts not built");
            return problem;
        }
        log.error("query failed", ex);
        return ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}

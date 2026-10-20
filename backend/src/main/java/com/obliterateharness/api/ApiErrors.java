package com.obliterateharness.api;

import com.obliterateharness.domain.DomainException;
import com.obliterateharness.domain.NotFoundException;
import com.obliterateharness.graph.ChangeExecutor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ChangeExecutor.ConflictException.class)
    public ProblemDetail conflict(ChangeExecutor.ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(org.neo4j.driver.exceptions.Neo4jException.class)
    public ProblemDetail neo4j(org.neo4j.driver.exceptions.Neo4jException e) {
        LoggerFactory.getLogger(ApiErrors.class).error("Neo4j error", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                com.obliterateharness.i18n.I18n.t("Graph database error: ", "Erro no banco de grafo: ") + e.getMessage());
    }

    @ExceptionHandler(DomainException.class)
    public ProblemDetail domain(DomainException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}

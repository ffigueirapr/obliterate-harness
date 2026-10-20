package com.obliterateharness.domain;

/** Erro de regra de negócio. A mensagem deve explicar como corrigir — ela chega à LLM. */
public class DomainException extends RuntimeException {
    public DomainException(String message) {
        super(message);
    }
}

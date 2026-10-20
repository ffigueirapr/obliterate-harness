package com.obliterateharness.domain;

/** Quem originou uma mudança: humano (UI/REST), LLM (MCP) ou o próprio sistema. */
public enum Actor {
    human, llm, system
}

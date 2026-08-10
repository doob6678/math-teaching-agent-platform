package com.doob.mathagent.agent.service;

/** Thrown before a provider call when the signed plan is outside its token or configured cost budget. */
public final class AgentBudgetExceededException extends IllegalStateException {

    /** Creates a safe budget rejection. */
    public AgentBudgetExceededException(String message) {
        super(message);
    }
}

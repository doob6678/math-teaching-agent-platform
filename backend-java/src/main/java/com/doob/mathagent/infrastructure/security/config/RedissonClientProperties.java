package com.doob.mathagent.infrastructure.security.config;

/**
 * Redisson connection properties.
 */
public class RedissonClientProperties {

    /** Redis server address in Redisson format, for example redis://127.0.0.1:6379. */
    private String address = "redis://127.0.0.1:6379";

    /**
     * Returns the Redis server address.
     *
     * @return Redis server address
     */
    public String getAddress() {
        return address;
    }

    /**
     * Sets the Redis server address.
     *
     * @param address Redis server address
     */
    public void setAddress(String address) {
        this.address = address;
    }
}

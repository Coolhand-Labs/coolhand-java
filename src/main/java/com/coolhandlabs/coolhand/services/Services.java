package com.coolhandlabs.coolhand.services;

import com.coolhandlabs.coolhand.CoolhandException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

final class Services {
    private Services() {}

    /** The elements of a bare JSON array response. */
    static List<JsonNode> items(JsonNode body, String noun) {
        if (!body.isArray()) {
            throw new CoolhandException(noun + " response was not a JSON array");
        }
        List<JsonNode> out = new ArrayList<>(body.size());
        body.forEach(out::add);
        return out;
    }
}

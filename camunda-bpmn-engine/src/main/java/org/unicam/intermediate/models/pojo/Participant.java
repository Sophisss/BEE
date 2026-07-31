package org.unicam.intermediate.models.pojo;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Setter
@Getter
public class Participant {
    private String id;
    private String name;
    private String position;
    private Map<String, Object> attributes = new HashMap<>();
}

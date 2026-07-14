package com.projectkorra.projectkorra.storage;

import java.util.Map;
import java.util.UUID;

public interface TempElementRepository {

    Map<String, Long> load(UUID uuid);

    void replace(UUID uuid, Map<String, Long> values);
}


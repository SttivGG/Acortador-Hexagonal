package com.example.demo.application.ports;

// Abstract Factory: construye la familia de adaptadores de persistencia/caché.
public interface LinkInfrastructureFactory {
    LinkRepository createRepository();
    LinkCache createCache();
}

# Operating System Simulator

Simulador educativo incremental de mecanismos internos de un sistema operativo, desarrollado como parte del curso de Principios/Sistemas Operativos.

## Desarrollo incremental

El proyecto se desarrollará mediante cuatro fases:

1. Tarea Programada 1
2. Proyecto 1
3. Proyecto 2
4. Proyecto 3

Cada etapa extenderá progresivamente la funcionalidad implementada en la etapa anterior.

## Estrategia de ramas

El repositorio utiliza el siguiente flujo base:

```text
main
  -> dev
      -> feature/*
```

- `main`: contiene las versiones estables y los estados entregables del proyecto.
- `dev`: funciona como rama de integración para cambios terminados y verificados.
- `feature/*`: contiene cambios pequeños y específicos desarrollados a partir de `dev`.

## Flujo de integración

Los cambios se integran mediante Pull Requests siguiendo el flujo:

```text
feature/*
    -> Pull Request
        -> dev
            -> Pull Request
                -> main
```

Las ramas `main` y `dev` están protegidas mediante reglas de GitHub para evitar integraciones directas fuera del flujo establecido.

## Control de versiones

Los commits deben ser pequeños, coherentes y describir claramente la intención del cambio realizado.

Se utilizarán convenciones simples como:

- `feat:` para nuevas funcionalidades.
- `fix:` para correcciones.
- `refactor:` para reorganizaciones internas.
- `test:` para pruebas.
- `docs:` para documentación.
- `chore:` para configuración y mantenimiento.

Las descripciones de los commits se redactarán en español.

## Hitos académicos

Cuando corresponda, los estados entregados de cada etapa podrán preservarse mediante Git tags o GitHub Releases asociados a los siguientes hitos:

```text
tarea-1
proyecto-1
proyecto-2
proyecto-3
```

Estos tags se crearán únicamente cuando cada entrega haya sido completada y validada.

## Portabilidad

El repositorio debe mantenerse independiente del sistema operativo y del entorno de desarrollo utilizado.

No deben versionarse configuraciones personales del IDE, archivos generados, logs, archivos temporales ni credenciales.

Cuando se incorpore Maven Wrapper, sus archivos deberán mantenerse versionados para facilitar la ejecución reproducible del proyecto.

## Pull Requests

Las integraciones hacia `dev` y `main` se realizan mediante Pull Requests.

Actualmente no se requieren aprobaciones externas obligatorias debido a que el desarrollo es principalmente individual.

Las validaciones automáticas mediante GitHub Actions se incorporarán posteriormente, cuando exista una base ejecutable del proyecto con build y pruebas reales.

## Estado actual

El repositorio se encuentra en su fase inicial de configuración.

Actualmente se está preparando la base de control de versiones y portabilidad antes de comenzar la implementación del simulador.
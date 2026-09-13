# 🖥️ Operating System Simulator

> Simulador educativo de conceptos fundamentales de Sistemas Operativos desarrollado como aplicación de escritorio en Java.

---

## 👤 Información académica

| Información | Detalle |
|---|---|
| **Estudiante** | Raúl Alfaro Rodríguez |
| **Carnet** | 2023060456 |
| **Curso** | Sistemas Operativos |
| **Asignación** | Tarea Programada 1 |
| **Video Demostrativo** | https://youtu.be/XBlNZpDldgQ |

---

## 📌 Objetivo

El objetivo de esta Tarea Programada es desarrollar un **simulador educativo de los mecanismos básicos involucrados en la ejecución de un proceso dentro de un Sistema Operativo**.

La aplicación permite representar de forma gráfica la interacción entre:

- CPU simulada.
- Memory simulada.
- Registros `AC`, `IR`, `PC`, `AX`, `BX`, `CX` y `DX`.
- Process Control Block (`PCB`).
- Programas escritos en formato `.asm`.
- Carga de instrucciones en User Memory.
- Ejecución manual mediante `Step`.
- Ejecución automática.
- Estados del simulador y del proceso.
- Representación binaria de las instrucciones.

> El proyecto es una **simulación académica** y no corresponde a la implementación de un Kernel o Sistema Operativo real.

---

## 🎯 Alcance de la Tarea 1

La primera etapa del proyecto implementa la ejecución de **un único proceso** dentro de una máquina simulada.

El flujo principal es:

```text
Configurar Memory
        ↓
Inicializar simulador
        ↓
Seleccionar archivo .asm
        ↓
Importar y parsear instrucciones
        ↓
Cargar programa en User Memory
        ↓
Crear PCB
        ↓
Start
        ↓
RUNNING
        ↓
Step / Automatic
        ↓
Ejecutar instrucciones
        ↓
FINISHED
```

### Instrucciones soportadas

| Instruction | Operación |
|---|---|
| `MOV R, N` | `R = N` |
| `LOAD R` | `AC = R` |
| `STORE R` | `R = AC` |
| `ADD R` | `AC = AC + R` |
| `SUB R` | `AC = AC - R` |

Los valores lógicos manejados por los registros se encuentran en el rango:

```text
-127 .. 127
```

---

## 🧠 Componentes simulados

### CPU

La CPU simulada utiliza los siguientes registros:

```text
AC  → Accumulator
IR  → Instruction Register
PC  → Program Counter

AX
BX
CX
DX  → General Purpose Registers
```

### Memory

La memoria es configurable antes de iniciar la simulación y posee un mínimo de:

```text
128 posiciones
```

Se divide conceptualmente en:

```text
┌─────────────────────────┐
│      Kernel Memory      │
├─────────────────────────┤
│       User Memory       │
└─────────────────────────┘
```

Cada instrucción semántica ocupa:

```text
1 Instruction = 1 Memory position
```

### PCB

El `Process Control Block` mantiene la información necesaria para controlar el proceso:

```text
PID
Process State
Program Start Address
Instruction Count
Program End
Saved PC
```

---

## 🛠️ Tecnologías utilizadas

| Tecnología | Uso |
|---|---|
| **Java 25** | Lenguaje principal y lógica del simulador |
| **JavaFX 25** | Interfaz gráfica de escritorio |
| **FXML** | Definición estructural de la GUI |
| **JavaFX CSS** | Estilos visuales de la aplicación |
| **Maven** | Build, dependencias y ejecución |
| **Maven Wrapper** | Ejecución reproducible sin instalación global de Maven |
| **JUnit 5** | Automated testing |
| **Git** | Control de versiones |
| **GitHub** | Repositorio remoto y Pull Requests |
| **GitHub Actions** | Continuous Integration |

---

## 🏗️ Arquitectura del software

El proyecto utiliza una **Layered Architecture**, complementada con principios de `MVC` en la capa de Presentation.

```text
┌──────────────────────────────┐
│         Presentation         │
│   JavaFX · FXML · CSS · MVC  │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│          Application         │
│ Use Cases · Orchestration    │
│ Lifecycle · Snapshots        │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│            Model             │
│ CPU · Memory · PCB           │
│ Instructions · Execution     │
└──────────────────────────────┘

┌──────────────────────────────┐
│        Infrastructure        │
│ ASM Parser · File Import     │
└──────────────────────────────┘
```

### Presentation

Responsable de la interacción gráfica con el usuario.

Incluye:

- JavaFX.
- FXML.
- CSS.
- `SimulatorController`.
- Visualización del estado de CPU, Memory, PCB e instrucciones.

### Application

Coordina los casos de uso del simulador.

Entre sus responsabilidades se encuentran:

- inicialización;
- carga del programa;
- control del lifecycle;
- ejecución;
- generación de snapshots;
- coordinación general de la simulación.

### Model

Contiene la lógica principal e independiente de la interfaz gráfica:

- Memory.
- CPU Registers.
- Instructions.
- PCB.
- Execution Engine.
- Binary Codec.

### Infrastructure

Gestiona mecanismos externos, principalmente:

- lectura de archivos `.asm`;
- conversión del contenido ASM hacia objetos `Instruction`.

---

## 🎨 Principios de diseño

El proyecto fue desarrollado procurando mantener:

- Object-Oriented Programming.
- Separation of Concerns.
- High Cohesion.
- Low Coupling.
- Encapsulation.
- Defensive Programming.
- SOLID de manera pragmática.
- Dependency Injection manual.
- Componentes independientes de la GUI.
- Testing de la lógica principal.

Una de las principales decisiones de diseño consiste en mantener la lógica del simulador separada de JavaFX.

Por ejemplo:

```text
JavaFX GUI
    ↓
Application
    ↓
Model
```

De esta manera, CPU, Memory, PCB y Execution Engine pueden funcionar y ser probados independientemente de la interfaz gráfica.

---

## ⚙️ Modelo de ejecución

La ejecución es coordinada principalmente mediante:

```text
SimulatorController
        ↓
SimulatorOrchestrator
        ↓
ExecutionEngine
        ↓
CPU / Memory / PCB
        ↓
SimulatorSnapshot
        ↓
GUI
```

### Manual Step

Cada `Step` ejecuta exactamente:

```text
1 semantic Instruction
```

### Automatic Execution

La ejecución automática utiliza un JavaFX `Timeline`.

```text
Timeline
   ↓
Step
   ↓
SimulatorOrchestrator
   ↓
ExecutionEngine
```

Esto permite reutilizar el mismo flujo de ejecución manual sin crear un segundo motor de ejecución.

---

## 🔢 Representación binaria

Las instrucciones poseen además una representación binaria educativa utilizando palabras de:

```text
8 bits
```

Formato principal:

```text
OOORR000
```

donde:

```text
OOO → Opcode
RR  → Register
000 → Reserved bits
```

Las instrucciones `LOAD`, `STORE`, `ADD` y `SUB` utilizan un `BinaryWord`.

`MOV` utiliza dos `BinaryWords`, aunque continúa ocupando únicamente:

```text
1 posición de Memory
```

---

# 🚀 Instalación y ejecución

## Requisitos

Antes de ejecutar el proyecto se requiere:

- **Git**
- **Eclipse Temurin OpenJDK 25** o una distribución compatible con Java 25.
- Sistema capaz de ejecutar aplicaciones JavaFX.

Maven no necesita instalarse globalmente, ya que el repositorio incluye **Maven Wrapper**.

Verificar Java:

```powershell
java --version
```

Debe utilizar Java 25.

---

## 1. Clonar el repositorio

```powershell
git clone https://github.com/rajami1205/Operating_System_Simulator.git
```

Ingresar al proyecto:

```powershell
cd Operating_System_Simulator
```

---

## 2. Ejecutar los tests

En Windows:

```powershell
.\mvnw.cmd test
```

Baseline validado para la Tarea 1:

```text
Tests:    539
Failures: 0
Errors:   0
Skipped:  0
```

---

## 3. Validar el proyecto

```powershell
.\mvnw.cmd verify
```

---

## 4. Ejecutar la aplicación

```powershell
.\mvnw.cmd javafx:run
```

Esto inicia la interfaz gráfica:

```text
Operating System Simulator
```

---

## 🖥️ Uso general

El flujo normal dentro de la aplicación es:

```text
1. Configurar Memory
2. Initialize
3. Browse
4. Seleccionar archivo .asm
5. Load Program
6. Start
7. Ejecutar mediante Step o Automatic
8. Observar CPU, Registers, PCB y Memory
9. Llegar a FINISHED
10. Reset
```

Ejemplo de programa:

```asm
MOV AX, 5
LOAD AX
MOV BX, 3
ADD BX
STORE CX
SUB AX
```

---

# 🌿 Estrategia Git

El repositorio utiliza un flujo basado en tres niveles de branches:

```text
main
 ↑
dev
 ↑
feature/*
```

### `main`

Contiene las versiones estables y aprobadas del proyecto.

No se desarrolla directamente sobre esta branch.

### `dev`

Branch principal de integración.

Recibe las features previamente desarrolladas y verificadas.

### `feature/*`

Cada funcionalidad o cambio importante se desarrolla de manera aislada.

Ejemplos:

```text
feature/memory-model
feature/cpu-registers
feature/asm-parser
feature/execution-engine
feature/automatic-execution
```

---

## 🔀 Pull Requests

La integración sigue el flujo:

```text
feature/*
    │
    │ Pull Request
    ▼
   dev
    │
    │ Pull Request
    ▼
  main
```

De esta forma se evita modificar directamente las branches principales y cada cambio puede revisarse antes de ser integrado.

---

## 🤖 Continuous Integration

El repositorio utiliza **GitHub Actions**.

Los Pull Requests dirigidos hacia:

```text
dev
main
```

ejecutan automáticamente el workflow:

```text
Maven Build & Test
```

El proceso utiliza:

```text
Ubuntu
Eclipse Temurin Java 25
Maven Wrapper
```

y ejecuta:

```text
mvn verify
```

La integración solo debe realizarse cuando la validación finaliza correctamente.

---

## ✅ Estado de la Tarea 1

La implementación actual fue validada mediante:

```text
539 automated tests
+
manual end-to-end acceptance
```

Resultado:

```text
Tests     : 539
Failures  : 0
Errors    : 0
Skipped   : 0

BUILD SUCCESS
```

Se validaron, entre otros:

- ejecución manual;
- ejecución automática;
- Pause / Resume;
- finalización natural;
- errores recuperables;
- errores de ejecución;
- Reset;
- representación de CPU, Memory y PCB;
- carga de programas ASM.

---

## 📚 Evolución del proyecto

Este proyecto fue diseñado de forma incremental.

```text
Tarea Programada 1 ✅
        ↓
Proyecto 1
        ↓
Proyecto 2
        ↓
Proyecto 3
```

La arquitectura actual funciona como base para incorporar posteriormente mecanismos más avanzados de Sistemas Operativos sin reconstruir los componentes ya desarrollados.

---

### 🎓 Sistemas Operativos — Tarea Programada 1

**Raúl Alfaro Rodríguez**  
**Carnet: 2023060456**

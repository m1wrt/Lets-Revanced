# LETS Sentence Synthesis Engine

This folder contains resources and experiments for the Lets sentence synthesis engine that turns keywords into natural phrases to support everyday communication.

## Project Structure

- `es/` and `en/`: language vocabularies, sentence datasets, and model configuration.
- `gemma3-finetuned.ipynb`: Gemma 3 experimentation notebook.

Datasets map keywords (`palabras`) to generated sentences (`oracion`) in Spanish and English. The language configuration files specify each language's vocabulary, dataset, and model file.

## Model Development

| Modelos: | Rendimiento | ¿Vale la pena? |
| --- | ---: | ---: |
| Gemma3-1b | Excelente, presenta una gran acertividad  | Si |
| Gemma3-270m | Pesimo, cuenta con alucinaciones | No |
| Qwen3-0.6b | Pesimo, cuenta con alucinaciones | No |

> [!NOTE]
> This release is compiled; however, it should not be used in production as it is not a complete version and the application is still under active development.
# Tachimanga Extension - Hitomi.la

## Cómo publicar (solo 3 pasos)



### 2. Activar GitHub Pages en el repo
- Ir a **Settings > Pages**
- Source: **Deploy from a branch**
- Branch: **repo** / **/ (root)**
- Guardar

### 3. Agregar en Tachimanga
Una vez que GitHub Actions termine (1-2 minutos), agregar esta URL en Tachimanga:
```
https://raw.githubusercontent.com/thaboy9090-bit/tachimanga-hitomi/repo/index.min.json
```

---

## Estructura del proyecto

```
tachimanga-extension/
├── .github/workflows/build_push.yml   ← CI: compila y publica automáticamente
├── scripts/generate_index.py          ← Genera index.min.json
├── src/en/hitomi/
│   ├── AndroidManifest.xml
│   ├── build.gradle
│   ├── res/mipmap-*/                  ← Agregar ic_launcher.png aquí
│   └── src/.../Hitomi.kt             ← Lógica principal
├── build.gradle                       ← Configuración raíz
├── common.gradle                      ← Config Android compartida
├── settings.gradle                    ← Lista de módulos
└── gradle.properties
```

## Cómo funciona el CI

1. Push a `main` → GitHub Actions se activa
2. Compila el APK con Gradle
3. Firma el APK automáticamente
4. Genera `index.min.json`
5. Publica todo en la rama `repo`

## Agregar más extensiones

1. Copiar la carpeta `src/en/hitomi` y renombrarla
2. Editar `build.gradle` y `Hitomi.kt`
3. Agregar el módulo en `settings.gradle`
4. Push → el CI lo compila y publica automáticamente

## Icono (opcional pero recomendado)

Agregar `ic_launcher.png` en cada carpeta `res/mipmap-*`:
- `mipmap-mdpi`: 48×48 px
- `mipmap-hdpi`: 72×72 px
- `mipmap-xhdpi`: 96×96 px
- `mipmap-xxhdpi`: 144×144 px
- `mipmap-xxxhdpi`: 192×192 px

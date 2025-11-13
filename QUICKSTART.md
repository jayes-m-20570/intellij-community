# Quick Start Guide: Running IntelliJ IDEA Community Edition

This guide provides step-by-step instructions for running the IntelliJ IDEA Community Edition from source code.

## Prerequisites

Before you begin, ensure you have:

1. **Java Development Kit (JDK) 17 or later**
   - JetBrains Runtime 17 is recommended (will be auto-downloaded when you build)
   - Verify: `java -version` should show version 17+

2. **Git** installed on your system

3. **Sufficient System Resources**
   - At least **8GB RAM** (recommended: 16GB or more)
   - At least **10GB free disk space** for source code and build artifacts

4. **IntelliJ IDEA** (version 2023.2 or newer)
   - Can use Community Edition or Ultimate Edition
   - Download from: https://www.jetbrains.com/idea/download/

## Quick Start Steps

### 1. Clone the Repository

If you haven't already cloned the repository:

```bash
git clone https://github.com/JetBrains/intellij-community.git
cd intellij-community
```

**Speed Tip**: Use shallow clone if you don't need full history:
```bash
git clone --depth 1 https://github.com/JetBrains/intellij-community.git
cd intellij-community
```

**Windows Users**: Before cloning, configure git:
```bash
git config --global core.longpaths true
git config --global core.autocrlf input
```

### 2. Get Android Plugin Modules (Optional)

If you need Android support, run:

**Linux/macOS:**
```bash
./getPlugins.sh --shallow
```

**Windows:**
```bash
getPlugins.bat --shallow
```

### 3. Open the Project in IntelliJ IDEA

1. Launch IntelliJ IDEA
2. Select **File → Open**
3. Navigate to and select the `intellij-community` directory
4. Click **OK**
5. If prompted about missing or outdated plugins (especially Kotlin), install/update them and restart IntelliJ IDEA

### 4. Configure the Project

When opening the project for the first time:

1. **JetBrains Runtime**: IntelliJ IDEA will automatically suggest downloading JetBrains Runtime 17 when you first build. Accept this to download.

2. **Maven Repository** (if Maven plugin is disabled):
   - Go to **File → Settings → Appearance & Behavior → Path Variables**
   - Add variable `MAVEN_REPOSITORY` pointing to `~/.m2/repository` (or `%USERPROFILE%\.m2\repository` on Windows)

3. **Memory Settings** (recommended for faster builds):
   - Go to **File → Settings → Build, Execution, Deployment → Compiler**
   - Increase "User-local build process heap size" to 3000 MB (if you have enough RAM)
   - If low on RAM, disable "Compile independent modules in parallel"

## Running IntelliJ IDEA from Source

### Method 1: Using the Pre-configured Run Configuration (Recommended)

1. In IntelliJ IDEA, locate the run configuration dropdown in the toolbar
2. Select **"IDEA"** from the dropdown
3. Click the green **Run** button (▶) or press **Shift+F10**
4. Wait for the build to complete and the new IntelliJ IDEA instance to start

**Note**: The first build may take 10-30 minutes depending on your hardware. Subsequent runs will be much faster.

### Method 2: Using Menu

1. In IntelliJ IDEA, go to **Run → Run...**
2. Select **"IDEA"** from the list
3. The IDE will build and launch

### Other Available Run Configurations

- **IDEA with Android**: Runs IntelliJ IDEA with Android plugin support
- **IDEA with Python plugin**: Runs IntelliJ IDEA with Python plugin support
- **IDEA K2**: Runs IntelliJ IDEA with the new K2 Kotlin compiler

## Building the Project

### Build the Project (No Installation)

To compile the source code without creating installers:

1. In IntelliJ IDEA, select **Build → Build Project** or press **Ctrl+F9** (Cmd+F9 on macOS)

### Build Installation Packages

To create installer packages for distribution:

**Linux/macOS:**
```bash
./installers.cmd -Dintellij.build.target.os=current
```

**Windows:**
```bash
installers.cmd -Dintellij.build.target.os=current
```

**Build Options:**
- Current OS only: `-Dintellij.build.target.os=current`
- Incremental build: `-Dintellij.build.incremental.compilation=true`
- See [BuildOptions.kt](platform/build-scripts/src/org/jetbrains/intellij/build/BuildOptions.kt) for all options

## Running Tests

### Run Tests from IntelliJ IDEA

1. Configure JUnit template:
   - Go to **Run → Edit Configurations... → Templates → JUnit**
   - Set Working directory: `<IDEA_HOME>/bin`
   - Set VM options: `-ea`

2. Right-click on a test file/class/method and select **Run**

### Run Tests from Command Line

**All tests:**
```bash
./tests.cmd
```

**Specific test:**
```bash
./tests.cmd -Dintellij.build.test.patterns=com.intellij.util.ArrayUtilTest
```

**Incremental build:**
```bash
./tests.cmd -Dintellij.build.incremental.compilation=true
```

See [TestingOptions.kt](platform/build-scripts/src/org/jetbrains/intellij/build/TestingOptions.kt) for all test options.

## Troubleshooting

### Build Fails with "Out of Memory" Error

**Solution:**
1. Increase heap size: **File → Settings → Build, Execution, Deployment → Compiler**
2. Set "User-local build process heap size" to 3000 MB or higher
3. Or disable "Compile independent modules in parallel"

### "Required Plugin Not Found" Error

**Solution:**
1. Go to **File → Settings → Plugins**
2. Search for the missing plugin (usually Kotlin)
3. Install/Enable the plugin and restart IntelliJ IDEA

### JetBrains Runtime Issues

**Solution:**
1. Enable internal mode: Add `idea.is.internal=true` to `idea.properties`
2. Go to **File → Project Structure → SDKs**
3. Find `jbr-17` SDK, click Browse
4. Select **Download...** and choose JetBrains Runtime 17 (without JCEF)

### Build Takes Too Long

**Solutions:**
- Use incremental builds: `-Dintellij.build.incremental.compilation=true`
- Increase heap size in compiler settings
- Close unnecessary applications to free up RAM
- Use SSD for faster I/O

### Cannot Push/Pull from Git

**Windows Solution:**
```bash
git config --global core.longpaths true
```

## Additional Resources

- **README.md**: Detailed build instructions and system requirements
- **CONTRIBUTING.md**: Guidelines for contributing code
- **IntelliJ Platform SDK Docs**: https://plugins.jetbrains.com/docs/intellij/
- **JetBrains Open Source**: https://www.jetbrains.com/opensource/idea/

## Quick Reference Commands

| Task | Command |
|------|---------|
| Build project | `Build → Build Project` in IDE |
| Run IDE | Select "IDEA" config and click Run |
| Build installers | `./installers.cmd -Dintellij.build.target.os=current` |
| Run tests | `./tests.cmd` |
| Run specific test | `./tests.cmd -Dintellij.build.test.patterns=TestClassName` |

## Next Steps

After successfully running IntelliJ IDEA from source:

1. **Explore the codebase**: Start with the `platform` directory for core functionality
2. **Read the contributing guide**: See [CONTRIBUTING.md](CONTRIBUTING.md)
3. **Join the community**: Visit https://www.jetbrains.com/opensource/idea/
4. **Report issues**: Use the GitHub issue tracker

---

**Need Help?** 
- Check the [README.md](README.md) for detailed information
- Visit https://www.jetbrains.com/opensource/idea/ for community support
- Review existing issues on GitHub

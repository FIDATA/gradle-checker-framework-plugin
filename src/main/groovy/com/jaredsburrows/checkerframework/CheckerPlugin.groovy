package com.jaredsburrows.checkerframework

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.AbstractCompile

final class CheckerPlugin implements Plugin<Project> {
  // Handles pre-3.0 and 3.0+, "com.android.base" was added in AGP 3.0
  private static final List<String> ANDROID_IDS = [
    'com.android.application',
    'com.android.feature',
    'com.android.instantapp',
    'com.android.library',
    'com.android.test'
  ]
  // Checker Framework configurations and dependencies
  private final static String LIBRARY_VERSION = 'latest.release'
  private final static String ANNOTATED_JDK_NAME_JDK7 = 'jdk7'
  private final static String ANNOTATED_JDK_NAME_JDK8 = 'jdk8'
  private final static String ANNOTATED_JDK_CONFIGURATION = 'checkerFrameworkAnnotatedJDK'
  private final static String ANNOTATED_JDK_CONFIGURATION_DESCRIPTION = 'A copy of JDK classes with Checker Framework type qualifiers inserted.'
  private final static String JAVAC_CONFIGURATION = 'checkerFrameworkJavac'
  private final static String JAVAC_CONFIGURATION_DESCRIPTION = 'A customization of the OpenJDK javac compiler with additional support for type annotations.'
  private final static String CONFIGURATION = 'checkerFramework'
  private final static String CONFIGURATION_DESCRIPTION = 'The Checker Framework: custom pluggable types for Java.'
  private final static String ANNOTATION_PROCESSOR_CONFIGURATION = 'annotationProcessor'
  private final static String TEST_ANNOTATION_PROCESSOR_CONFIGURATION = 'testAnnotationProcessor'
  private final static String JAVA_COMPILE_CONFIGURATION = 'compile'
  private final static String COMPILER_DEPENDENCY = "org.checkerframework:compiler:${LIBRARY_VERSION}"
  private final static String CHECKER_DEPENDENCY = "org.checkerframework:checker:${LIBRARY_VERSION}"
  private final static String CHECKER_QUAL_DEPENDENCY = "org.checkerframework:checker-qual:${LIBRARY_VERSION}"

  @Override void apply(Project project) {
    (ANDROID_IDS + 'java').each { id ->
      project.plugins.withId(id) { configureProject(project) }
    }
  }

  private static configureProject(def project) {
    project.plugins.apply 'net.ltgt.apt'

    JavaVersion javaVersion =
        project.extensions.findByName('android')?.compileOptions?.sourceCompatibility ?:
        project.convention.findByName('jdk')?.sourceCompatibility ?:
        JavaVersion.current()

    // Check for Java 7 or Java 8 to make sure to get correct annotations dependency
    String jdkVersion
    if (javaVersion.java7) {
      jdkVersion = ANNOTATED_JDK_NAME_JDK7
    } else if (javaVersion.java8) {
      jdkVersion = ANNOTATED_JDK_NAME_JDK8
    } else {
      throw new IllegalStateException('Checker plugin only supports Java 7 and Java 8 projects.')
    }

    CheckerExtension userConfig = project.extensions.create('checkerFramework', CheckerExtension)

    // Create a map of the correct configurations with dependencies
    Map<Map, String> dependencyMap = [
      [name: ANNOTATED_JDK_CONFIGURATION, description: ANNOTATED_JDK_CONFIGURATION_DESCRIPTION]: "org.checkerframework:${jdkVersion}:${LIBRARY_VERSION}",
      [name: JAVAC_CONFIGURATION, description: JAVAC_CONFIGURATION_DESCRIPTION]                : COMPILER_DEPENDENCY,
      [name: CONFIGURATION, description: ANNOTATED_JDK_CONFIGURATION_DESCRIPTION]              : CHECKER_DEPENDENCY,
      [name: JAVA_COMPILE_CONFIGURATION, description: CONFIGURATION_DESCRIPTION]               : CHECKER_QUAL_DEPENDENCY,
      [name: ANNOTATION_PROCESSOR_CONFIGURATION]                                               : CHECKER_DEPENDENCY,
      [name: TEST_ANNOTATION_PROCESSOR_CONFIGURATION]                                          : CHECKER_DEPENDENCY
    ]

    // Now, apply the dependencies to project
    dependencyMap.each { configuration, dependency ->
      // User could have an existing configuration, the plugin will add to it
      if (project.configurations.find { it.name == configuration.name }) {
        project.configurations[configuration.name].dependencies.add(
          project.dependencies.create(dependency))
      } else {
        // If the user does not have the configuration, the plugin will create it
        project.configurations.create(configuration.name) { files ->
          files.description = configuration.description
          files.visible = false
          files.defaultDependencies { dependencies ->
            dependencies.add(project.dependencies.create(dependency))
          }
        }
      }
    }

    // Apply checker to project
    project.gradle.projectsEvaluated {
      project.tasks.withType(AbstractCompile).all { compile ->
        compile.options.with {
          compilerArgs << "-Xbootclasspath/p:${project.configurations[ANNOTATED_JDK_CONFIGURATION].asPath}"
          if (!userConfig.checkers.empty) {
            compilerArgs << '-processor' << userConfig.checkers.join(',')
          }

          ANDROID_IDS.each { id ->
            project.plugins.withId(id) {
              bootClasspath = [
                System.getProperty('sun.boot.class.path'),
                project.configurations[JAVAC_CONFIGURATION].asPath,
                bootClasspath
              ].join(File.pathSeparator)
            }
          }
          fork = true
          //        forkOptions.jvmArgs += ["-Xbootclasspath/p:${project.configurations[JAVAC_CONFIGURATION].asPath}"]
        }
      }
    }
  }
}

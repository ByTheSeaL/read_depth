plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.android")
}

// One version for the whole repo: VERSION holds major.minor, CI supplies the
// build number (github.run_number). Local builds are "0.1-dev".
val baseVersion = rootProject.file("../VERSION").readText().trim()
val buildNumber = (System.getenv("BUILD_NUMBER") ?: "0").toInt()

// The release key comes from CI secrets. Without it the APK is debug-signed,
// which installs fine but can't update an app signed with the release key.
val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")

android {
	namespace  = "com.readdepth"
	compileSdk = 34

	defaultConfig {
		applicationId = "com.readdepth"
		minSdk        = 26
		targetSdk     = 34
		versionCode   = maxOf(1, buildNumber)
		versionName   = if (buildNumber > 0) "$baseVersion.$buildNumber" else "$baseVersion-dev"
	}

	signingConfigs {
		create("release") {
			if (keystorePath != null) {
				storeFile     = file(keystorePath)
				storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
				keyAlias      = System.getenv("ANDROID_KEY_ALIAS")
				keyPassword   = System.getenv("ANDROID_KEY_PASSWORD")
			}
		}
	}

	buildTypes {
		release {
			isMinifyEnabled = false
			signingConfig   = signingConfigs.getByName(if (keystorePath != null) "release" else "debug")
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	kotlinOptions {
		jvmTarget = "17"
	}

	// The unit tests read the same cases as the extension's tests.
	sourceSets["test"].resources.srcDir("../../shared")
}

dependencies {
	// Deliberately minimal, like Excerpt Anywhere: networking is
	// HttpURLConnection and JSON is the platform's org.json.
	implementation("androidx.appcompat:appcompat:1.6.1")

	testImplementation("junit:junit:4.13.2")
	// The platform's org.json is a stub off-device; the tests need the real one.
	testImplementation("org.json:json:20240303")
}

plugins { application }
description = "Deliberately vulnerable synthetic teaching fixture; never a product dependency."
dependencies { implementation(platform(libs.spring.boot.bom)); implementation("tools.jackson.core:jackson-databind") }
application { mainClass.set("io.github.gbalazsfalvi.slotkeeper.training.TrainingTargetMain") }

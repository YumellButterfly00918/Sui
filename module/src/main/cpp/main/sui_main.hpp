/*
 * This file is part of Sui.
 *
 * Sui is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Sui is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Sui.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2021 Sui Contributors
 */

#include <cstdlib>
#include <cstring>
#include <stdio.h>
#include <logging.h>
#include <unistd.h>
#include <sched.h>
#include <app_process.h>
#include <sys/stat.h>

/*
 * argv[1]: path of the module, such as /data/adb/modules/zygisk-sui
 */
static int sui_main(int argc, char **argv) {
    LOGI("Sui starter begin: %s", argv[1]);

    if (daemon(false, false) != 0) {
        PLOGE("daemon");
        return EXIT_FAILURE;
    }

    wait_for_zygote();

    if (access("/data/adb/sui", F_OK) != 0) {
        mkdir("/data/adb/sui", 0700);
    }
    if (chown("/data/adb/sui", 0, 0) != 0) {
        PLOGE("chown /data/adb/sui");
    }
    if (chmod("/data/adb/sui", 0700) != 0) {
        PLOGE("chmod /data/adb/sui");
    }

    const char *legacy_database_files[] = {
            "/data/system/sui/sui.db",
            "/data/system/sui/sui.db-wal",
            "/data/system/sui/sui.db-shm",
            "/data/system/sui/sui.db-journal",
    };
    const char *database_files[] = {
            "/data/adb/sui/sui.db",
            "/data/adb/sui/sui.db-wal",
            "/data/adb/sui/sui.db-shm",
            "/data/adb/sui/sui.db-journal",
    };

    if (access(database_files[0], F_OK) != 0 && access(legacy_database_files[0], F_OK) == 0) {
        for (size_t i = 0; i < sizeof(database_files) / sizeof(database_files[0]); ++i) {
            if (access(legacy_database_files[i], F_OK) == 0
                    && rename(legacy_database_files[i], database_files[i]) != 0) {
                PLOGE("migrate %s to %s", legacy_database_files[i], database_files[i]);
            }
        }
    }

    for (const char *database_file : database_files) {
        if (access(database_file, F_OK) == 0) {
            if (chown(database_file, 0, 0) != 0) {
                PLOGE("chown %s", database_file);
            }
            if (chmod(database_file, 0600) != 0) {
                PLOGE("chmod %s", database_file);
            }
        }
    }

    auto root_path = argv[1];

    char dex_path[PATH_MAX]{0};
    strcpy(dex_path, root_path);
    strcat(dex_path, "/sui.dex");

    app_process(dex_path, root_path, "rikka.sui.server.Starter", "sui");

    return EXIT_SUCCESS;
}

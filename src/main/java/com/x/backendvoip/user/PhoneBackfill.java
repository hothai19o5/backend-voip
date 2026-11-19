package com.x.backendvoip.user;

import com.x.backendvoip.calling.PhoneCallingProperties;
import com.x.backendvoip.common.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;

@Slf4j
@Component
@ConditionalOnProperty("app.phone-calls.backfill-file")
@RequiredArgsConstructor
class PhoneBackfill implements ApplicationRunner {
    private final PhoneCallingProperties properties;
    private final UserRepository users;
    private final Environment environment;
    private final ApplicationContext context;

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (properties.enabled() || context instanceof WebApplicationContext)
            throw new IllegalStateException("Run phone backfill in maintenance mode with web disabled and PHONE_CALLS_ENABLED=false.");
        var lines = Files.readAllLines(Path.of(environment.getRequiredProperty("app.phone-calls.backfill-file")));
        var remaining = new HashMap<String, AppUser>();
        var taken = new HashSet<String>();
        for (var user : users.findAll()) {
            if (user.getPhoneE164() == null) remaining.put(user.getUsername(), user);
            else taken.add(user.getPhoneE164());
        }
        var updates = new LinkedHashMap<AppUser, String>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (index == 0 && line.startsWith("\uFEFF")) line = line.substring(1);
            if (line.isBlank()) continue;
            String[] columns = line.split("\t", -1);
            if (columns.length != 2) throw invalid(index, "Expected username and phone separated by a tab.");
            var user = remaining.remove(columns[0].strip().toLowerCase(Locale.ROOT));
            if (user == null) throw invalid(index, "Unknown username, duplicate username or account already has a phone.");
            String number;
            try {
                number = PhoneNumbers.parse(columns[1]).e164();
            } catch (ApiException error) {
                throw invalid(index, "Invalid phone number.");
            }
            if (!taken.add(number)) throw invalid(index, "Phone is already assigned or repeated in the file.");
            updates.put(user, number);
        }
        if (!remaining.isEmpty())
            throw new IllegalArgumentException("Backfill file is missing " + remaining.size() + " accounts without a phone.");
        boolean apply = environment.getProperty("app.phone-calls.backfill-apply", Boolean.class, false);
        if (apply) {
            updates.forEach(AppUser::assignPhone);
            users.flush();
        }
        log.info("Phone backfill validated {} accounts. Apply mode: {}", updates.size(), apply);
    }

    private static IllegalArgumentException invalid(int zeroBasedLine, String issue) {
        return new IllegalArgumentException("Backfill file line " + (zeroBasedLine + 1) + ". " + issue);
    }
}

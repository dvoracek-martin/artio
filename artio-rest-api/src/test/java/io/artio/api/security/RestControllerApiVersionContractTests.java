package io.artio.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class RestControllerApiVersionContractTests {
    @Test
    void everyRestControllerRouteUsesTheSingleDevelopmentApiVersion() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var controllers = scanner.findCandidateComponents("io.artio.api");
        var unversioned = new ArrayList<String>();
        var incorrectlyVersioned = new ArrayList<String>();
        int routeCount = 0;

        for (var candidate : controllers) {
            var controller = ClassUtils.forName(candidate.getBeanClassName(), getClass().getClassLoader());
            var controllerMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            var prefixes = paths(controllerMapping);
            for (var prefix : prefixes) {
                if (!isDevelopmentApiPrefix(prefix)) {
                    incorrectlyVersioned.add(controller.getSimpleName() + " class mapping => " + prefix);
                }
            }
            for (var method : controller.getDeclaredMethods()) {
                var methodMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (methodMapping == null) continue;
                routeCount++;
                for (var prefix : prefixes) {
                    for (var route : paths(methodMapping)) {
                        var fullPath = (prefix + "/" + route).replaceAll("/{2,}", "/");
                        if (!isDevelopmentApiPrefix(fullPath)) {
                            unversioned.add(controller.getSimpleName() + "#" + method.getName() + " => " + fullPath);
                        }
                        if (route.matches(".*(?:^|/)v\\d+(?:/|$).*") || !prefix.contains("/api/v1")) {
                            incorrectlyVersioned.add(controller.getSimpleName() + "#" + method.getName()
                                    + " => class=" + prefix + ", method=" + route);
                        }
                    }
                }
            }
        }

        assertThat(routeCount).as("discovered REST controller routes").isPositive();
        assertThat(unversioned)
                .as("REST controllers must expose only the development /api/v1 surface")
                .isEmpty();
        assertThat(incorrectlyVersioned)
                .as("REST version belongs to the controller mapping; method routes cannot introduce another version")
                .isEmpty();
    }

    private static boolean isDevelopmentApiPrefix(String path) {
        return path.equals("/api/v1") || path.startsWith("/api/v1/");
    }

    private static List<String> paths(RequestMapping mapping) {
        if (mapping == null) return List.of("");
        var declared = mapping.path().length == 0 ? mapping.value() : mapping.path();
        return declared.length == 0 ? List.of("") : List.of(declared);
    }
}

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
        int routeCount = 0;

        for (var candidate : controllers) {
            var controller = ClassUtils.forName(candidate.getBeanClassName(), getClass().getClassLoader());
            var controllerMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            var prefixes = paths(controllerMapping);
            for (var method : controller.getDeclaredMethods()) {
                var methodMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (methodMapping == null) continue;
                routeCount++;
                for (var prefix : prefixes) {
                    for (var route : paths(methodMapping)) {
                        var fullPath = (prefix + "/" + route).replaceAll("/{2,}", "/");
                        if (!fullPath.equals("/api/v1") && !fullPath.startsWith("/api/v1/")) {
                            unversioned.add(controller.getSimpleName() + "#" + method.getName() + " => " + fullPath);
                        }
                    }
                }
            }
        }

        assertThat(routeCount).as("discovered REST controller routes").isPositive();
        assertThat(unversioned)
                .as("REST controllers must expose only the development /api/v1 surface")
                .isEmpty();
    }

    private static List<String> paths(RequestMapping mapping) {
        if (mapping == null) return List.of("");
        var declared = mapping.path().length == 0 ? mapping.value() : mapping.path();
        return declared.length == 0 ? List.of("") : List.of(declared);
    }
}

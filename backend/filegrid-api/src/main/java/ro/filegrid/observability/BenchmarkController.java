package ro.filegrid.observability;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/v1/admin/benchmark/run} (ADMIN, enforced by {@code SecurityConfig} on {@code /api/v1/admin/**}). */
@RestController
@Profile("db")
@RequestMapping("/api/v1/admin")
class BenchmarkController {

    private final BenchmarkService benchmark;

    BenchmarkController(BenchmarkService benchmark) {
        this.benchmark = benchmark;
    }

    @PostMapping("/benchmark/run")
    BenchmarkService.Result run() {
        return benchmark.run();
    }
}

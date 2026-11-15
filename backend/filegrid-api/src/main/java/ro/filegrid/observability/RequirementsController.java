package ro.filegrid.observability;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import ro.filegrid.security.Role;

/** {@code GET /api/v1/stats/requirements} (DISPATCH și ADMIN): starea celor 4 cerințe de business. */
@RestController
@Profile("db")
@RequestMapping("/api/v1/stats")
class RequirementsController {

    private final RequirementsService service;

    RequirementsController(RequirementsService service) {
        this.service = service;
    }

    @GetMapping("/requirements")
    RequirementsService.Requirements requirements(HttpServletRequest request) {
        if (!request.isUserInRole(Role.DISPATCH.name()) && !request.isUserInRole(Role.ADMIN.name())) {
            throw new AccessDeniedException("Accesul la statistici necesită rolul DISPATCH sau ADMIN.");
        }
        return service.evaluate();
    }
}

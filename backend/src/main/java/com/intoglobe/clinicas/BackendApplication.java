package com.intoglobe.clinicas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Punto de entrada del backend de Galeno.
 *
 * <p><strong>Se excluye {@link UserDetailsServiceAutoConfiguration} a propósito</strong>
 * (R-SEG-09, ADR-014). Esa autoconfiguración crea un usuario en memoria cuando no encuentra
 * un {@code UserDetailsService} y lo anuncia en el log con la línea
 * {@code Using generated security password: <uuid>}: una credencial en el log de cada
 * arranque, que es exactamente lo que la auditoría de seguridad prohíbe. En esta API ese
 * usuario no autentica nada, porque el token lo emite el AuthService externo y el backend
 * solo valida su firma; con {@code UserDetailsServiceAutoConfiguration} el riesgo era puro
 * y el beneficio nulo.</p>
 *
 * <p>La exclusión vive aquí y no en {@code application.properties} porque ese archivo es
 * local y no se versiona (ADR-006): una propiedad de seguridad que un desarrollador puede
 * no tener en su copia no es una decisión de seguridad.</p>
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}

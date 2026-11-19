package com.intoglobe.clinicas.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.repository.OrganizacionRepository;
import com.intoglobe.clinicas.repository.UsuarioRepository;
import com.intoglobe.clinicas.service.UsuarioService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * S1: o se crean las dos filas, o ninguna. La prueba del fallo a mitad de la transacción.
 *
 * <p>Se sustituye <strong>solo</strong> {@link UsuarioService} por un doble que falla al crear el
 * administrador: la organización, el repositorio y la transacción son los de producción. El doble
 * hace dos cosas, y las dos importan:</p>
 * <ul>
 *   <li>Desde dentro de su propia llamada afirma que la organización <em>ya está escrita</em> y
 *       visible en la transacción en curso. Eso demuestra que el orden es el que el contrato pide
 *       —primero la clínica, después su administración— y que la fila intermedia existió de verdad;
 *       un fallo antes de escribir nada no probaría nada.</li>
 *   <li>Después lanza, que es lo que dispara el rollback de la transacción que abrió el servicio de
 *       onboarding.</li>
 * </ul>
 *
 * <p>La afirmación final es la que cierra S1: la organización intermedia desapareció. Sin
 * {@code @Transactional} en el servicio, la primera escritura quedaría confirmada y la clínica
 * existiría sin nadie que pudiera entrar a administrarla, que es exactamente el registro
 * inservible que la spec prohíbe.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:onboarding_rollback;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OnboardingRollbackTest {

    private static final String RUTA_REGISTRO = "/api/v1/onboarding/registro-clinica";
    private static final String CUERPO = """
            {"nombreClinica":"Clínica Rollback","plan":"CLINICA",
             "nombreAdmin":"Administración","correoAdmin":"rollback@example.com"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizacionRepository organizacionRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @MockitoBean
    private UsuarioService usuarioService;

    @Test
    @DisplayName("si falla el alta del administrador, la organización recién escrita no queda en la base")
    void siFallaElAdministradorNoQuedaLaOrganizacion() throws Exception {
        when(usuarioService.crearAdministrador(any(Organizacion.class), anyString(), anyString()))
                .thenAnswer(invocacion -> {
                    Organizacion organizacion = invocacion.getArgument(0);
                    assertThat(organizacion.getId()).as("la organización ya tiene identificador").isNotNull();
                    assertThat(organizacionRepository.buscarPorSlug("clinica-rollback"))
                            .as("la organización ya está escrita dentro de la misma transacción")
                            .isPresent()
                            .get()
                            .extracting(Organizacion::getId)
                            .isEqualTo(organizacion.getId());
                    throw new IllegalStateException("fallo simulado al crear el administrador");
                });

        long organizacionesAntes = organizacionRepository.count();
        long cuentasAntes = usuarioRepository.count();

        // Sin un manejador genérico de excepciones, MockMvc propaga el fallo en lugar de
        // convertirlo en respuesta: el 500 lo escribiría el contenedor. Lo que importa aquí es que
        // la excepción sube y que, al subir, la transacción se deshace.
        assertThatThrownBy(() -> mockMvc.perform(post(RUTA_REGISTRO)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO)))
                .as("la petición falla: no responde 201")
                .hasRootCauseInstanceOf(IllegalStateException.class);

        assertThat(organizacionRepository.buscarPorSlug("clinica-rollback"))
                .as("el rollback deshace la organización intermedia: no hay clínica sin administración")
                .isEmpty();
        assertThat(organizacionRepository.count()).isEqualTo(organizacionesAntes);
        assertThat(usuarioRepository.count()).isEqualTo(cuentasAntes);
    }

    @Test
    @DisplayName("el nombre y el correo recortado viajan al alta del administrador")
    void elAltaDelAdministradorRecibeLosDatosDelRegistro() throws Exception {
        when(usuarioService.crearAdministrador(any(Organizacion.class), anyString(), anyString()))
                .thenAnswer(invocacion -> {
                    throw new IllegalStateException("solo se comprueba la llamada");
                });

        assertThatThrownBy(() -> mockMvc.perform(post(RUTA_REGISTRO)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"nombreClinica":"Clínica Rollback","plan":"CLINICA",
                         "nombreAdmin":"Administración","correoAdmin":"  rollback@example.com  "}
                        """)))
                .hasRootCauseInstanceOf(IllegalStateException.class);

        org.mockito.Mockito.verify(usuarioService)
                .crearAdministrador(any(Organizacion.class), eq("Administración"), eq("rollback@example.com"));
    }
}

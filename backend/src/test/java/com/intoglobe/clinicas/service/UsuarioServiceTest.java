package com.intoglobe.clinicas.service;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.intoglobe.clinicas.dto.request.UsuarioRequest;
import com.intoglobe.clinicas.dto.response.UsuarioResponse;
import com.intoglobe.clinicas.exception.CorreoDuplicadoEnOrganizacionException;
import com.intoglobe.clinicas.exception.MedicoRequeridoException;
import com.intoglobe.clinicas.exception.OperacionSobreLaPropiaCuentaException;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.exception.UltimaAdministradoraException;
import com.intoglobe.clinicas.model.Consultorio;
import com.intoglobe.clinicas.model.Medico;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.PlanTipo;
import com.intoglobe.clinicas.model.RolUsuario;
import com.intoglobe.clinicas.model.Usuario;
import com.intoglobe.clinicas.repository.MedicoRepository;
import com.intoglobe.clinicas.repository.OrganizacionRepository;
import com.intoglobe.clinicas.repository.UsuarioRepository;
import com.intoglobe.clinicas.security.AuthenticatedUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link UsuarioService}: una por regla de negocio del módulo.
 *
 * <p>No levantan contexto de Spring: los tres repositorios se simulan con Mockito. La identidad
 * ({@code id}) y {@code createdAt} los asigna Hibernate al persistir y la entidad no expone
 * setters, así que las fixtures con aspecto de registro persistido las aportan espías de Mockito
 * ({@code spy} más {@code doReturn}); no se pueblan entidades por reflexión.</p>
 *
 * <p>La cobertura de la sección 2 de la card se reparte así: R1 (organización del claim), R2
 * (correo normalizado y único por organización), R3 (conjunto cerrado de roles), R4 (médico
 * obligatorio y de la misma organización), R5 (roles que no atienden), R6 (identidad generada),
 * R7 (baja y reactivación idempotentes) y R8 (última administradora). R10 —una cuenta de otra
 * organización responde 404— se prueba en las operaciones por id porque es la otra mitad del
 * aislamiento por tenant. R11 —nadie se da de baja ni se cambia el rol a sí mismo— tiene su propio
 * bloque, con el actor construido desde el {@code authUserId} de la cuenta, que es el claim
 * {@code sub} del token y no la clave de la fila: ahí se fija que R11 gana a R8 cuando ambas
 * aplicarían y que editar el propio correo sin cambiar el rol sigue permitido.</p>
 *
 * <p>Las validaciones Jakarta del DTO ({@code @NotBlank}, {@code @Size}, {@code @NotNull}) y el
 * 400 de un rol que no se puede deserializar no se prueban aquí: son del contrato de entrada y se
 * ejercitan en los tests MVC.</p>
 */
@ExtendWith(MockitoExtension.class)
class UsuarioServiceTest {

    private static final String EMAIL = "laura.mendez@example.com";
    private static final String NOMBRE = "Laura Méndez";
    private static final String TELEFONO = "+52 55 5555 9876";

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private OrganizacionRepository organizacionRepository;

    @Mock
    private MedicoRepository medicoRepository;

    @InjectMocks
    private UsuarioService usuarioService;

    // ─────────────────────────────────────────
    // R1 · la organización sale del claim, nunca del path
    // ─────────────────────────────────────────

    @Test
    @DisplayName("R1: crear con la organización de otro tenant responde 403 sin consultar la base")
    void crear_shouldThrowAccessDeniedAndNotTouchTheDatabase_whenPathOrganizationIsNotTheTenant() {
        var organizacionAjena = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        assertThatThrownBy(() -> usuarioService.crear(organizacionAjena, tenantId, requestAdmin(EMAIL)))
                .as("el path es una afirmación del cliente: desmentirla no cuesta ninguna lectura")
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(organizacionRepository, usuarioRepository, medicoRepository);
    }

    @Test
    @DisplayName("R1: listar bajo la organización de otro tenant responde 403 sin consultar la base")
    void listar_shouldThrowAccessDeniedAndNotTouchTheDatabase_whenPathOrganizationIsNotTheTenant() {
        var organizacionAjena = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        assertThatThrownBy(() -> usuarioService.listarPorOrganizacion(organizacionAjena, tenantId))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(organizacionRepository, usuarioRepository, medicoRepository);
    }

    // ─────────────────────────────────────────
    // R2 · correo normalizado a minúsculas y único por organización
    // ─────────────────────────────────────────

    @Test
    @DisplayName("R2: crear guarda el correo en minúsculas aunque llegue con mayúsculas")
    void crear_shouldPersistTheEmailNormalizedToLowerCase() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(usuarioRepository.buscarPorOrganizacionYEmail(organizacionId, EMAIL))
                .thenReturn(Optional.empty());
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        var respuesta = usuarioService.crear(organizacionId, organizacionId,
                requestAdmin("Laura.Mendez@Example.COM"));

        assertThat(respuesta.email()).isEqualTo(EMAIL);
        var captor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail())
                .as("la normalización es la misma al guardar que al buscar")
                .isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("S6: crear con espacios alrededor del correo lo guarda recortado")
    void crear_shouldPersistTheEmailTrimmed_whenItArrivesWithSurroundingSpaces() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(usuarioRepository.buscarPorOrganizacionYEmail(organizacionId, EMAIL))
                .thenReturn(Optional.empty());
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        var respuesta = usuarioService.crear(organizacionId, organizacionId,
                requestAdmin("  laura.mendez@example.com  "));

        assertThat(respuesta.email()).isEqualTo(EMAIL);
        var captor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail())
                .as("los espacios de los extremos no llegan a la columna")
                .isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("S6: un correo con espacios alrededor es el mismo que sin ellos, así que responde 409")
    void crear_shouldThrowCorreoDuplicado_whenTheSameEmailArrivesWithSurroundingSpaces() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionTransitoria();
        var existente = usuarioTransitorio(organizacion, null, EMAIL, RolUsuario.ADMIN, UUID.randomUUID());
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(usuarioRepository.buscarPorOrganizacionYEmail(organizacionId, EMAIL))
                .thenReturn(Optional.of(existente));

        assertThatThrownBy(() -> usuarioService.crear(organizacionId, organizacionId,
                requestAdmin("  LAURA.MENDEZ@example.com  ")))
                .as("la búsqueda usa el correo recortado y en minúsculas: el espacio no esquiva la unicidad")
                .isInstanceOf(CorreoDuplicadoEnOrganizacionException.class)
                .hasMessageContaining(EMAIL);
        verify(usuarioRepository).buscarPorOrganizacionYEmail(organizacionId, EMAIL);
        verify(usuarioRepository, never()).save(any(Usuario.class));
    }

    @Test
    @DisplayName("R2: crear con un correo ya ocupado en la organización responde 409 y no guarda")
    void crear_shouldThrowCorreoDuplicadoAndNotSave_whenTheEmailIsAlreadyUsedInTheOrganization() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionTransitoria();
        var existente = usuarioTransitorio(organizacion, null, EMAIL, RolUsuario.ADMIN, UUID.randomUUID());
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(usuarioRepository.buscarPorOrganizacionYEmail(organizacionId, EMAIL))
                .thenReturn(Optional.of(existente));

        assertThatThrownBy(() -> usuarioService.crear(organizacionId, organizacionId,
                requestAdmin("LAURA.MENDEZ@example.com")))
                .as("el correo se busca ya normalizado, así que la caja no esquiva la unicidad")
                .isInstanceOf(CorreoDuplicadoEnOrganizacionException.class)
                .hasMessageContaining(EMAIL);
        verify(usuarioRepository, never()).save(any(Usuario.class));
    }

    @Test
    @DisplayName("R2: el mismo correo en otra organización no es un duplicado")
    void crear_shouldAcceptTheSameEmail_whenItBelongsToAnotherOrganization() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(usuarioRepository.buscarPorOrganizacionYEmail(organizacionId, EMAIL))
                .thenReturn(Optional.empty());
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        var respuesta = usuarioService.crear(organizacionId, organizacionId, requestAdmin(EMAIL));

        assertThat(respuesta.email()).isEqualTo(EMAIL);
        verify(usuarioRepository).buscarPorOrganizacionYEmail(organizacionId, EMAIL);
    }

    @Test
    @DisplayName("R2: actualizar con el correo de otra cuenta de la organización responde 409")
    void actualizar_shouldThrowCorreoDuplicado_whenAnotherAccountUsesTheEmail() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var cuenta = usuarioPersistido(organizacion, null, "propia@example.com", RolUsuario.ADMIN,
                UUID.randomUUID());
        var otra = usuarioPersistido(organizacion, null, EMAIL, RolUsuario.PACIENTE, UUID.randomUUID());
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));
        when(usuarioRepository.buscarPorOrganizacionYEmail(organizacionId, EMAIL))
                .thenReturn(Optional.of(otra));

        assertThatThrownBy(() -> usuarioService.actualizar(actor(organizacionId), cuenta.getId(),
                requestAdmin(EMAIL)))
                .isInstanceOf(CorreoDuplicadoEnOrganizacionException.class);
        verify(usuarioRepository, never()).save(any(Usuario.class));
        assertThat(cuenta.getEmail()).isEqualTo("propia@example.com");
    }

    @Test
    @DisplayName("R2: actualizar la propia cuenta cambiando solo la caja del correo no es un duplicado")
    void actualizar_shouldAllowItsOwnEmail_whenOnlyTheCaseChanges() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var cuenta = usuarioPersistido(organizacion, null, EMAIL, RolUsuario.ADMIN, UUID.randomUUID());
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));
        when(usuarioRepository.buscarPorOrganizacionYEmail(organizacionId, EMAIL))
                .thenReturn(Optional.of(cuenta));
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        var respuesta = usuarioService.actualizar(actor(organizacionId), cuenta.getId(),
                requestAdmin("LAURA.MENDEZ@example.com"));

        assertThat(respuesta.email()).isEqualTo(EMAIL);
    }

    // ─────────────────────────────────────────
    // S1-S3 · el nombre y el teléfono de la persona
    // ─────────────────────────────────────────

    @Test
    @DisplayName("S1-S2: crear guarda el nombre y el teléfono que llegaron en el alta")
    void crear_shouldPersistTheNameAndPhoneOfThePerson() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(usuarioRepository.buscarPorOrganizacionYEmail(organizacionId, EMAIL))
                .thenReturn(Optional.empty());
        when(usuarioRepository.save(any(Usuario.class)))
                .thenAnswer(invocacion -> invocacion.getArgument(0));

        var respuesta = usuarioService.crear(organizacionId, organizacionId,
                new UsuarioRequest(EMAIL, RolUsuario.RECEPCIONISTA, null, NOMBRE, TELEFONO));

        var captor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).save(captor.capture());
        assertThat(captor.getValue().getNombre())
                .as("el nombre de la persona se persiste en la columna que existe desde la V10")
                .isEqualTo(NOMBRE);
        assertThat(captor.getValue().getTelefono()).isEqualTo(TELEFONO);
        assertThat(respuesta.nombre()).isEqualTo(NOMBRE);
        assertThat(respuesta.telefono()).isEqualTo(TELEFONO);
    }

    @Test
    @DisplayName("S1: el teléfono es opcional y la cuenta se crea sin él")
    void crear_shouldPersistANullPhone_whenItWasNotRegistered() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(usuarioRepository.buscarPorOrganizacionYEmail(organizacionId, EMAIL))
                .thenReturn(Optional.empty());
        when(usuarioRepository.save(any(Usuario.class)))
                .thenAnswer(invocacion -> invocacion.getArgument(0));

        var respuesta = usuarioService.crear(organizacionId, organizacionId,
                new UsuarioRequest(EMAIL, RolUsuario.ADMIN, null, NOMBRE, null));

        var captor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).save(captor.capture());
        assertThat(captor.getValue().getTelefono()).isNull();
        assertThat(respuesta.nombre()).isEqualTo(NOMBRE);
        assertThat(respuesta.telefono()).isNull();
    }

    @Test
    @DisplayName("S5: actualizar completa el nombre de una cuenta anterior y guarda el teléfono")
    void actualizar_shouldPersistTheNameOfALegacyAccount() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var cuenta = usuarioPersistido(organizacion, null, EMAIL, RolUsuario.RECEPCIONISTA,
                UUID.randomUUID());
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));
        when(usuarioRepository.buscarPorOrganizacionYEmail(organizacionId, EMAIL))
                .thenReturn(Optional.of(cuenta));
        when(usuarioRepository.save(any(Usuario.class)))
                .thenAnswer(invocacion -> invocacion.getArgument(0));

        assertThat(cuenta.getNombre())
                .as("las cuentas anteriores a la V10 no tienen nombre: la lista las muestra por correo")
                .isNull();

        var respuesta = usuarioService.actualizar(actor(organizacionId), cuenta.getId(),
                new UsuarioRequest(EMAIL, RolUsuario.RECEPCIONISTA, null, NOMBRE, TELEFONO));

        assertThat(cuenta.getNombre())
                .as("guardar una cuenta anterior obliga a completarle el nombre")
                .isEqualTo(NOMBRE);
        assertThat(cuenta.getTelefono()).isEqualTo(TELEFONO);
        assertThat(respuesta.nombre()).isEqualTo(NOMBRE);
        assertThat(respuesta.telefono()).isEqualTo(TELEFONO);
    }

    @Test
    @DisplayName("S6: crearAdministrador conserva el nombre del registro y nace sin teléfono")
    void crearAdministrador_shouldKeepTheNameAndLeaveThePhoneEmpty() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        when(usuarioRepository.buscarPorOrganizacionYEmail(organizacionId, EMAIL))
                .thenReturn(Optional.empty());
        when(usuarioRepository.save(any(Usuario.class)))
                .thenAnswer(invocacion -> invocacion.getArgument(0));

        var respuesta = usuarioService.crearAdministrador(organizacion, NOMBRE, EMAIL);

        var captor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).save(captor.capture());
        assertThat(captor.getValue().getNombre()).isEqualTo(NOMBRE);
        assertThat(captor.getValue().getTelefono())
                .as("el formulario público no pide teléfono: su cuenta nace sin él")
                .isNull();
        assertThat(captor.getValue().getRol()).isEqualTo(RolUsuario.ADMIN);
        assertThat(respuesta.nombre()).isEqualTo(NOMBRE);
    }

    // ─────────────────────────────────────────
    // R3 · rol del conjunto cerrado
    // ─────────────────────────────────────────

    @Test
    @DisplayName("R3: el enum de roles es espejo exacto del CHECK del esquema")
    void rolUsuario_shouldMirrorTheCheckOfTheSchema() {
        assertThat(RolUsuario.values())
                .as("un rol fuera de este conjunto no se puede deserializar: la petición responde 400 "
                        + "antes de llegar al servicio, y el CHECK de la base lo rechazaría después")
                .containsExactly(RolUsuario.ADMIN, RolUsuario.MEDICO, RolUsuario.PACIENTE,
                        RolUsuario.RECEPCIONISTA);
    }

    // ─────────────────────────────────────────
    // R4 · médico obligatorio y de la misma organización
    // ─────────────────────────────────────────

    @Test
    @DisplayName("R4: crear un MEDICO sin medicoId responde 400 y no guarda")
    void crear_shouldThrowMedicoRequeridoAndNotSave_whenRolIsMedicoWithoutMedicoId() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionTransitoria();
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));

        assertThatThrownBy(() -> usuarioService.crear(organizacionId, organizacionId,
                requestMedico(EMAIL, null)))
                .isInstanceOf(MedicoRequeridoException.class)
                .hasMessageContaining("medicoId");
        verify(usuarioRepository, never()).save(any(Usuario.class));
    }

    @Test
    @DisplayName("R4: crear un MEDICO con el médico de otra organización responde 404 y no guarda")
    void crear_shouldThrowResourceNotFoundAndNotSave_whenMedicoBelongsToAnotherOrganization() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionTransitoria();
        var medicoAjeno = medicoPersistido(organizacionConId(UUID.randomUUID()));
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(medicoRepository.findById(medicoAjeno.getId())).thenReturn(Optional.of(medicoAjeno));

        assertThatThrownBy(() -> usuarioService.crear(organizacionId, organizacionId,
                requestMedico(EMAIL, medicoAjeno.getId())))
                .as("un médico ajeno responde 404 y nunca 403: un 403 confirmaría que existe")
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Medico");
        verify(usuarioRepository, never()).save(any(Usuario.class));
    }

    @Test
    @DisplayName("R4: crear un MEDICO con un médico inexistente responde 404 y no guarda")
    void crear_shouldThrowResourceNotFoundAndNotSave_whenMedicoDoesNotExist() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionTransitoria();
        var medicoId = UUID.randomUUID();
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> usuarioService.crear(organizacionId, organizacionId,
                requestMedico(EMAIL, medicoId)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(medicoId.toString());
        verify(usuarioRepository, never()).save(any(Usuario.class));
    }

    @Test
    @DisplayName("R4: crear un MEDICO con su médico de la organización lo asocia")
    void crear_shouldAttachTheMedico_whenItBelongsToTheOrganization() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var medico = medicoPersistido(organizacion);
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(medicoRepository.findById(medico.getId())).thenReturn(Optional.of(medico));
        when(usuarioRepository.buscarPorOrganizacionYEmail(organizacionId, EMAIL))
                .thenReturn(Optional.empty());
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        var respuesta = usuarioService.crear(organizacionId, organizacionId,
                requestMedico(EMAIL, medico.getId()));

        assertThat(respuesta.medicoId()).isEqualTo(medico.getId());
        assertThat(respuesta.rol()).isEqualTo(RolUsuario.MEDICO);
    }

    // ─────────────────────────────────────────
    // R5 · los demás roles pueden llevar médico o no
    // ─────────────────────────────────────────

    @Test
    @DisplayName("R5: un ADMIN sin medicoId es una cuenta válida")
    void crear_shouldAllowNonMedicalRolesWithoutMedico() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(usuarioRepository.buscarPorOrganizacionYEmail(organizacionId, EMAIL))
                .thenReturn(Optional.empty());
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        var respuesta = usuarioService.crear(organizacionId, organizacionId, requestAdmin(EMAIL));

        assertThat(respuesta.medicoId()).isNull();
        verifyNoInteractions(medicoRepository);
    }

    @Test
    @DisplayName("R5: un ADMIN con médico asignado es la persona que administra y además atiende")
    void crear_shouldAllowAdminWithMedicoOfItsOwnOrganization() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var medico = medicoPersistido(organizacion);
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(medicoRepository.findById(medico.getId())).thenReturn(Optional.of(medico));
        when(usuarioRepository.buscarPorOrganizacionYEmail(organizacionId, EMAIL))
                .thenReturn(Optional.empty());
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        var respuesta = usuarioService.crear(organizacionId, organizacionId,
                new UsuarioRequest(EMAIL, RolUsuario.ADMIN, medico.getId(), NOMBRE, null));

        assertThat(respuesta.rol()).isEqualTo(RolUsuario.ADMIN);
        assertThat(respuesta.medicoId()).isEqualTo(medico.getId());
    }

    // ─────────────────────────────────────────
    // R6 · identidad generada por el servicio
    // ─────────────────────────────────────────

    @Test
    @DisplayName("R6: crear genera un authUserId UUID v4 distinto por cuenta y no lo expone")
    void crear_shouldGenerateTheAuthUserId_andNotExposeIt() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(usuarioRepository.buscarPorOrganizacionYEmail(any(UUID.class), any(String.class)))
                .thenReturn(Optional.empty());
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        usuarioService.crear(organizacionId, organizacionId, requestAdmin("una@example.com"));
        usuarioService.crear(organizacionId, organizacionId, requestAdmin("otra@example.com"));

        var captor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository, times(2)).save(captor.capture());
        var generados = captor.getAllValues().stream().map(Usuario::getAuthUserId).toList();

        assertThat(generados).doesNotContainNull();
        assertThat(generados)
                .as("cada cuenta recibe su propia identidad: son dos identidades distintas")
                .doesNotHaveDuplicates();
        assertThat(generados)
                .as("en local lo genera la aplicación y es un UUID v4")
                .allSatisfy(authUserId -> assertThat(authUserId.version()).isEqualTo(4));
        assertThat(UsuarioResponse.class.getRecordComponents())
                .as("la identidad de autenticación no es un dato de negocio: no viaja en la respuesta")
                .extracting(RecordComponent::getName)
                .doesNotContain("authUserId");
    }

    // ─────────────────────────────────────────
    // R7 · baja y reactivación idempotentes
    // ─────────────────────────────────────────

    @Test
    @DisplayName("R7: la baja es lógica y llamarla dos veces no falla")
    void desactivar_shouldBeLogicalAndIdempotent() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var cuenta = usuarioPersistido(organizacion, null, EMAIL, RolUsuario.PACIENTE, UUID.randomUUID());
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        usuarioService.desactivar(actor(organizacionId), cuenta.getId());

        assertThat(cuenta.isActivo()).isFalse();
        assertThatCode(() -> usuarioService.desactivar(actor(organizacionId), cuenta.getId()))
                .as("la baja de una cuenta ya inactiva es una operación válida, no un error")
                .doesNotThrowAnyException();
        assertThat(cuenta.isActivo()).isFalse();
    }

    @Test
    @DisplayName("R7: la reactivación es idempotente y no duplica la cuenta")
    void activar_shouldBeIdempotent() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var cuenta = usuarioPersistido(organizacion, null, EMAIL, RolUsuario.PACIENTE, UUID.randomUUID());
        cuenta.desactivar();
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        usuarioService.activar(cuenta.getId(), organizacionId);

        assertThat(cuenta.isActivo()).isTrue();
        assertThatCode(() -> usuarioService.activar(cuenta.getId(), organizacionId))
                .as("reactivar una cuenta que ya está activa no es un error")
                .doesNotThrowAnyException();
        assertThat(cuenta.isActivo()).isTrue();
    }

    // ─────────────────────────────────────────
    // R8 · la última administradora activa está protegida
    // ─────────────────────────────────────────

    @Test
    @DisplayName("R8: dar de baja a la última ADMIN activa responde 409 y no la desactiva")
    void desactivar_shouldThrowUltimaAdministradoraAndNotSave_whenItIsTheOnlyActiveAdmin() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var cuenta = usuarioPersistido(organizacion, null, EMAIL, RolUsuario.ADMIN, UUID.randomUUID());
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));
        when(usuarioRepository.contarActivosPorOrganizacionYRol(organizacionId, RolUsuario.ADMIN))
                .thenReturn(1L);

        assertThatThrownBy(() -> usuarioService.desactivar(actor(organizacionId), cuenta.getId()))
                .as("sin ninguna administradora activa la organización quedaría inmanejable")
                .isInstanceOf(UltimaAdministradoraException.class)
                .hasMessageContaining("última");
        verify(usuarioRepository, never()).save(any(Usuario.class));
        assertThat(cuenta.isActivo()).isTrue();
    }

    @Test
    @DisplayName("R8: dar de baja a una ADMIN cuando hay otra activa sí se aplica")
    void desactivar_shouldDeactivateAdmin_whenAnotherActiveAdminExists() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var cuenta = usuarioPersistido(organizacion, null, EMAIL, RolUsuario.ADMIN, UUID.randomUUID());
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));
        when(usuarioRepository.contarActivosPorOrganizacionYRol(organizacionId, RolUsuario.ADMIN))
                .thenReturn(2L);
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        usuarioService.desactivar(actor(organizacionId), cuenta.getId());

        assertThat(cuenta.isActivo()).isFalse();
    }

    @Test
    @DisplayName("R8: dar de baja una ADMIN ya inactiva no consulta el conteo y no falla")
    void desactivar_shouldSkipTheGuard_whenTheOnlyAdminIsAlreadyInactive() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var cuenta = usuarioPersistido(organizacion, null, EMAIL, RolUsuario.ADMIN, UUID.randomUUID());
        cuenta.desactivar();
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        usuarioService.desactivar(actor(organizacionId), cuenta.getId());

        assertThat(cuenta.isActivo()).isFalse();
        verify(usuarioRepository, never())
                .contarActivosPorOrganizacionYRol(any(UUID.class), any(RolUsuario.class));
    }

    @Test
    @DisplayName("R8: retirarle el rol ADMIN a la última administradora responde 409 y no la modifica")
    void actualizar_shouldThrowUltimaAdministradoraAndNotSave_whenTheOnlyAdminLosesTheRole() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var cuenta = usuarioPersistido(organizacion, null, EMAIL, RolUsuario.ADMIN, UUID.randomUUID());
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));
        when(usuarioRepository.buscarPorOrganizacionYEmail(organizacionId, EMAIL))
                .thenReturn(Optional.of(cuenta));
        when(usuarioRepository.contarActivosPorOrganizacionYRol(organizacionId, RolUsuario.ADMIN))
                .thenReturn(1L);

        assertThatThrownBy(() -> usuarioService.actualizar(actor(organizacionId), cuenta.getId(),
                new UsuarioRequest(EMAIL, RolUsuario.PACIENTE, null, NOMBRE, null)))
                .as("cambiar el rol de la única administradora deja a la organización sin administración")
                .isInstanceOf(UltimaAdministradoraException.class)
                .hasMessageContaining("última");
        verify(usuarioRepository, never()).save(any(Usuario.class));
        assertThat(cuenta.getRol()).isEqualTo(RolUsuario.ADMIN);
    }

    @Test
    @DisplayName("R8: cambiar el rol de una ADMIN cuando hay otra activa sí se aplica y libera el médico")
    void actualizar_shouldChangeTheRole_whenAnotherActiveAdminExists() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var medico = medicoTransitorio(organizacion);
        var cuenta = usuarioPersistido(organizacion, medico, EMAIL, RolUsuario.ADMIN, UUID.randomUUID());
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));
        when(usuarioRepository.buscarPorOrganizacionYEmail(organizacionId, EMAIL))
                .thenReturn(Optional.of(cuenta));
        when(usuarioRepository.contarActivosPorOrganizacionYRol(organizacionId, RolUsuario.ADMIN))
                .thenReturn(2L);
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        var respuesta = usuarioService.actualizar(actor(organizacionId), cuenta.getId(),
                new UsuarioRequest(EMAIL, RolUsuario.PACIENTE, null, NOMBRE, null));

        assertThat(respuesta.rol()).isEqualTo(RolUsuario.PACIENTE);
        assertThat(respuesta.medicoId())
                .as("dejar de ser MEDICO libera la agenda: la fila del médico no se toca")
                .isNull();
    }

    // ─────────────────────────────────────────
    // R10 · una cuenta de otra organización responde 404
    // ─────────────────────────────────────────

    @Test
    @DisplayName("R10: buscar una cuenta de otra organización responde 404, no 403")
    void buscarPorId_shouldThrowResourceNotFound_whenTheAccountBelongsToAnotherOrganization() {
        var organizacion = organizacionConId(UUID.randomUUID());
        var cuenta = usuarioPersistido(organizacion, null, EMAIL, RolUsuario.ADMIN, UUID.randomUUID());
        var otroTenant = UUID.randomUUID();
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));

        assertThatThrownBy(() -> usuarioService.buscarPorId(cuenta.getId(), otroTenant))
                .as("un 403 confirmaría que la cuenta existe en otra clínica")
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Usuario");
    }

    @Test
    @DisplayName("R10: dar de baja una cuenta de otra organización responde 404 y no la toca")
    void desactivar_shouldThrowResourceNotFoundAndNotSave_whenTheAccountBelongsToAnotherOrganization() {
        var organizacion = organizacionConId(UUID.randomUUID());
        var cuenta = usuarioPersistido(organizacion, null, EMAIL, RolUsuario.ADMIN, UUID.randomUUID());
        var otroTenant = UUID.randomUUID();
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));

        assertThatThrownBy(() -> usuarioService.desactivar(actor(otroTenant), cuenta.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(usuarioRepository, never()).save(any(Usuario.class));
        assertThat(cuenta.isActivo()).isTrue();
    }

    // ─────────────────────────────────────────
    // R11 · nadie opera sobre su propia cuenta
    // ─────────────────────────────────────────

    @Test
    @DisplayName("R11: una cuenta no puede darse de baja a sí misma y la cuenta sigue activa")
    void desactivar_shouldThrowOperacionSobreLaPropiaCuenta_whenTheActorIsTheAccount() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var cuenta = usuarioPersistido(organizacion, null, EMAIL, RolUsuario.PACIENTE, UUID.randomUUID());
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));

        assertThatThrownBy(() -> usuarioService.desactivar(actorDe(cuenta, organizacionId), cuenta.getId()))
                .as("la baja tiene que hacerla otra cuenta con permisos")
                .isInstanceOf(OperacionSobreLaPropiaCuentaException.class)
                .hasMessageContaining("propia cuenta");
        verify(usuarioRepository, never()).save(any(Usuario.class));
        assertThat(cuenta.isActivo()).isTrue();
    }

    @Test
    @DisplayName("R11: con la última administradora dándose de baja a sí misma gana el mensaje de R11, no el de R8")
    void desactivar_shouldThrowR11BeforeR8_whenTheOnlyActiveAdminIsTheActor() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var cuenta = usuarioPersistido(organizacion, null, EMAIL, RolUsuario.ADMIN, UUID.randomUUID());
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));

        assertThatThrownBy(() -> usuarioService.desactivar(actorDe(cuenta, organizacionId), cuenta.getId()))
                .as("lo que desbloquea la operación es que la haga otra cuenta, no nombrar otra administradora")
                .isInstanceOf(OperacionSobreLaPropiaCuentaException.class)
                .isNotInstanceOf(UltimaAdministradoraException.class);
        verify(usuarioRepository, never())
                .contarActivosPorOrganizacionYRol(any(UUID.class), any(RolUsuario.class));
        verify(usuarioRepository, never()).save(any(Usuario.class));
        assertThat(cuenta.isActivo()).isTrue();
    }

    @Test
    @DisplayName("R11: una cuenta no puede cambiarse el rol a sí misma")
    void actualizar_shouldThrowOperacionSobreLaPropiaCuenta_whenTheActorChangesItsOwnRole() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var cuenta = usuarioPersistido(organizacion, null, EMAIL, RolUsuario.ADMIN, UUID.randomUUID());
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));

        assertThatThrownBy(() -> usuarioService.actualizar(actorDe(cuenta, organizacionId), cuenta.getId(),
                new UsuarioRequest(EMAIL, RolUsuario.PACIENTE, null, NOMBRE, null)))
                .as("un administrador que se quita el rol se deja fuera del sistema por un clic")
                .isInstanceOf(OperacionSobreLaPropiaCuentaException.class)
                .hasMessageContaining("su propia cuenta");
        verify(usuarioRepository, never()).save(any(Usuario.class));
        assertThat(cuenta.getRol()).isEqualTo(RolUsuario.ADMIN);
    }

    @Test
    @DisplayName("R11: editar la propia cuenta sin cambiar el rol sí está permitido")
    void actualizar_shouldAllowEditingItsOwnAccount_whenTheRoleDoesNotChange() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var cuenta = usuarioPersistido(organizacion, null, "propia@example.com", RolUsuario.PACIENTE,
                UUID.randomUUID());
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));
        when(usuarioRepository.buscarPorOrganizacionYEmail(organizacionId, EMAIL))
                .thenReturn(Optional.empty());
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        var respuesta = usuarioService.actualizar(actorDe(cuenta, organizacionId), cuenta.getId(),
                new UsuarioRequest(EMAIL, RolUsuario.PACIENTE, null, NOMBRE, null));

        assertThat(respuesta.email())
                .as("la regla solo protege la baja y el cambio de rol, no el resto de los datos")
                .isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("R11: el actor llega como sub del token (auth_user_id): no puede darse de baja a sí mismo")
    void desactivar_shouldThrow_whenActorSubIsTheAuthUserIdOfTheAccount() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var cuenta = usuarioPersistido(organizacion, null, "propia@example.com", RolUsuario.ADMIN, UUID.randomUUID());
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));
        var actor = new AuthenticatedUser(cuenta.getAuthUserId(), "propia@example.com", "ADMIN", organizacionId, null);
        assertThatThrownBy(() -> usuarioService.desactivar(actor, cuenta.getId()))
                .isInstanceOf(OperacionSobreLaPropiaCuentaException.class)
                .hasMessageContaining("propia cuenta");
        assertThat(cuenta.isActivo()).isTrue();
    }

    @Test
    @DisplayName("R11: el actor llega como sub del token (auth_user_id): no puede cambiarse el rol a sí mismo")
    void actualizar_shouldThrow_whenActorSubIsTheAuthUserIdOfTheAccount() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var cuenta = usuarioPersistido(organizacion, null, "propia@example.com", RolUsuario.ADMIN, UUID.randomUUID());
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));
        var actor = new AuthenticatedUser(cuenta.getAuthUserId(), "propia@example.com", "ADMIN", organizacionId, null);

        assertThatThrownBy(() -> usuarioService.actualizar(actor, cuenta.getId(),
                new UsuarioRequest("propia@example.com", RolUsuario.PACIENTE, null, NOMBRE, null)))
                .isInstanceOf(OperacionSobreLaPropiaCuentaException.class)
                .hasMessageContaining("su propia cuenta");
        assertThat(cuenta.getRol()).isEqualTo(RolUsuario.ADMIN);
    }

    // ─────────────────────────────────────────
    // Contrato de las operaciones de colección
    // ─────────────────────────────────────────

    @Test
    @DisplayName("crear responde 404 cuando la organización del path no existe")
    void crear_shouldThrowResourceNotFound_whenTheOrganizationDoesNotExist() {
        var organizacionId = UUID.randomUUID();
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> usuarioService.crear(organizacionId, organizacionId, requestAdmin(EMAIL)))
                .as("dejar que la clave foránea falle convertiría en 500 un error del cliente")
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Organizacion");
        verify(usuarioRepository, never()).save(any(Usuario.class));
    }

    @Test
    @DisplayName("listar devuelve las cuentas de la organización, activas e inactivas")
    void listar_shouldReturnActiveAndInactiveAccountsOfTheOrganization() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var activa = usuarioPersistido(organizacion, null, "activa@example.com", RolUsuario.ADMIN,
                UUID.randomUUID());
        var inactiva = usuarioPersistido(organizacion, null, "inactiva@example.com", RolUsuario.MEDICO,
                UUID.randomUUID());
        inactiva.desactivar();
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(usuarioRepository.listarPorOrganizacion(organizacionId)).thenReturn(List.of(activa, inactiva));

        var respuesta = usuarioService.listarPorOrganizacion(organizacionId, organizacionId);

        assertThat(respuesta).extracting(UsuarioResponse::email)
                .containsExactly("activa@example.com", "inactiva@example.com");
        assertThat(respuesta).extracting(UsuarioResponse::activo).containsExactly(true, false);
        assertThat(respuesta.get(0).id()).isEqualTo(activa.getId());
    }

    @Test
    @DisplayName("actualizar responde 404 cuando la cuenta no existe")
    void actualizar_shouldThrowResourceNotFound_whenTheAccountDoesNotExist() {
        var organizacionId = UUID.randomUUID();
        var cuentaId = UUID.randomUUID();
        when(usuarioRepository.findById(cuentaId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> usuarioService.actualizar(actor(organizacionId), cuentaId,
                requestAdmin(EMAIL)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Usuario");
        verify(usuarioRepository, never()).save(any(Usuario.class));
    }

    @Test
    @DisplayName("buscarPorId devuelve la cuenta del tenant con sus datos públicos")
    void buscarPorId_shouldReturnTheAccountOfTheTenant() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var medico = medicoPersistido(organizacion);
        var cuenta = usuarioPersistido(organizacion, medico, EMAIL, RolUsuario.MEDICO, UUID.randomUUID());
        when(usuarioRepository.findById(cuenta.getId())).thenReturn(Optional.of(cuenta));

        var respuesta = usuarioService.buscarPorId(cuenta.getId(), organizacionId);

        assertThat(respuesta.id()).isEqualTo(cuenta.getId());
        assertThat(respuesta.organizacionId()).isEqualTo(organizacionId);
        assertThat(respuesta.medicoId()).isEqualTo(medico.getId());
        assertThat(respuesta.email()).isEqualTo(EMAIL);
        assertThat(respuesta.rol()).isEqualTo(RolUsuario.MEDICO);
        assertThat(respuesta.activo()).isTrue();
    }

    // ─────────────────────────────────────────
    // Fixtures
    // ─────────────────────────────────────────

    /** Request de una cuenta administrativa sin médico asociado. */
    private static UsuarioRequest requestAdmin(String email) {
        return new UsuarioRequest(email, RolUsuario.ADMIN, null, NOMBRE, null);
    }

    /**
     * Actor autenticado del tenant indicado, con una cuenta distinta de la que se opera: es el caso
     * normal, donde la operación la hace otra persona.
     */
    private static AuthenticatedUser actor(UUID tenantId) {
        return new AuthenticatedUser(UUID.randomUUID(), "otra.cuenta@example.com", "ADMIN", tenantId, null);
    }

    /**
     * Actor autenticado cuya cuenta es la que se opera: el caso que rechaza R11.
     *
     * <p>El identificador del actor es el {@code sub} del token, que es el {@code auth_user_id} de la
     * cuenta y <strong>no</strong> su clave primaria: son dos columnas distintas y siempre diferentes.
     * Fabricar el actor con {@code getId()} deja la prueba ciega al defecto que este archivo tiene que
     * atrapar.</p>
     *
     * @param cuenta   cuenta operada
     * @param tenantId organización del token
     * @return el actor con la identidad de autenticación de la cuenta indicada
     */
    private static AuthenticatedUser actorDe(Usuario cuenta, UUID tenantId) {
        return new AuthenticatedUser(cuenta.getAuthUserId(), EMAIL, "ADMIN", tenantId, null);
    }

    /** Request de una cuenta de médico con el médico indicado (o sin él). */
    private static UsuarioRequest requestMedico(String email, UUID medicoId) {
        return new UsuarioRequest(email, RolUsuario.MEDICO, medicoId, NOMBRE, null);
    }

    /**
     * Organización sin identidad simulada: sirve cuando la prueba no lee su id.
     */
    private static Organizacion organizacionTransitoria() {
        return new Organizacion("Clínica Demo", PlanTipo.CLINICA, null);
    }

    /**
     * Organización con identidad simulada. Se usa un espía en vez de reflexión para respetar la
     * prohibición de poblar entidades por reflexión.
     */
    private static Organizacion organizacionConId(UUID id) {
        var organizacion = spy(organizacionTransitoria());
        doReturn(id).when(organizacion).getId();
        return organizacion;
    }

    /**
     * Médico transitorio, sin identidad simulada: sirve cuando la prueba no lee su id.
     */
    private static Medico medicoTransitorio(Organizacion organizacion) {
        var consultorio = new Consultorio(organizacion, "Consultorio Demo", null, null);
        return new Medico(organizacion, consultorio, "Laura Méndez", null, null, null, null);
    }

    /**
     * Médico con aspecto de registro persistido, en su organización y con su consultorio. El
     * consultorio es transitorio: esta prueba unitaria nunca lo persiste ni lo lee.
     */
    private static Medico medicoPersistido(Organizacion organizacion) {
        var medico = spy(medicoTransitorio(organizacion));
        doReturn(UUID.randomUUID()).when(medico).getId();
        return medico;
    }

    /**
     * Cuenta transitoria, sin identidad simulada: sirve cuando la prueba no lee su id.
     *
     * <p>Nace <strong>sin nombre ni teléfono</strong>, que es el estado de las cuentas anteriores a
     * la V10: la columna es nulable y ningún dato se inventa para rellenarla. La obligación del
     * nombre vive en el DTO del alta, no en la entidad.</p>
     */
    private static Usuario usuarioTransitorio(Organizacion organizacion, Medico medico, String email,
            RolUsuario rol, UUID authUserId) {
        return new Usuario(organizacion, medico, null, null, email, rol, authUserId);
    }

    /** Cuenta con aspecto de registro persistido: id simulado que conserva el dominio real. */
    private static Usuario usuarioPersistido(Organizacion organizacion, Medico medico, String email,
            RolUsuario rol, UUID authUserId) {
        var usuario = spy(usuarioTransitorio(organizacion, medico, email, rol, authUserId));
        doReturn(UUID.randomUUID()).when(usuario).getId();
        return usuario;
    }
}

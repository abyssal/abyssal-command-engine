package com.abyssaldev.abyssal_command_engine.framework.gateway

import com.abyssaldev.abyssal_command_engine.framework.gateway.command.GatewayCommandInternal
import com.abyssaldev.abyssal_command_engine.framework.common.CommandModule
import com.abyssaldev.abyssal_command_engine.framework.common.CommandRequest
import com.abyssaldev.abyssal_command_engine.framework.common.reflect.Description
import com.abyssaldev.abyssal_command_engine.framework.common.reflect.Name
import com.abyssaldev.abyssal_command_engine.framework.common.result.*
import com.abyssaldev.abyssal_command_engine.framework.gateway.reflect.GatewayCommand
import com.abyssaldev.abyssal_command_engine.framework.gateway.command.GatewayCommandFailure
import com.abyssaldev.abyssal_command_engine.framework.gateway.command.GatewayCommandParameter
import com.abyssaldev.abyssal_command_engine.framework.gateway.event.CommandEngineEventHandler
import com.abyssaldev.abyssal_command_engine.framework.gateway.event.DefaultCommandEngineEventHandler
import com.abyssaldev.abyssal_command_engine.framework.gateway.prefix.PrefixStrategy
import com.abyssaldev.abyssal_command_engine.framework.gateway.prefix.StaticPrefixStrategy
import com.abyssaldev.abyssal_command_engine.framework.gateway.reflect.Result
import com.abyssaldev.abyssal_command_engine.util.Loggable
import com.abyssaldev.abyssal_command_engine.util.getAnnotation
import com.abyssaldev.abyssal_command_engine.util.getAnnotations
import com.abyssaldev.abyssal_command_engine.util.trySendMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.typeOf

/**
 * A Discord event listener that can handle commands received over the gateway.
 */
class CommandEngine private constructor(
    /**
     * The current [PrefixStrategy].
     */
    val prefixStrategy: PrefixStrategy,

    /**
     * The [CommandModule]s in use by this engine.
     */
    val modules: List<CommandModule>,

    /**
     * The owner of this [CommandEngine].
     */
    val ownerId: String,

    /**
     * The event handler of this [CommandEngine].
     */
    val eventHandler: CommandEngineEventHandler,

    /**
     * The type parsers of this [CommandEngine].
     */
    val typeParsers: List<TypeParser<*>>
): Loggable, ListenerAdapter() {
    private val commands: List<GatewayCommandInternal>

    init {
        val gatewayCommands = mutableListOf<GatewayCommandInternal>()
        modules.forEach {
            it::class.memberFunctions.forEach { member ->
                val annot = member.annotations.getAnnotation<GatewayCommand>() ?: return@forEach
                val parameters = member.parameters.filter { param ->
                    param.kind == KParameter.Kind.VALUE && param.name != null && !param.type.isSubtypeOf(CommandRequest::class.createType())
                }.map { param ->
                    GatewayCommandParameter(
                        name = param.annotations.getAnnotation<Name>()?.name ?: param.name!!,
                        description = param.annotations.getAnnotation<Description>()?.description ?: "",
                        type = param.type.jvmErasure,
                        contracts = param.annotations.getAnnotations()
                    )
                }
                GatewayCommandInternal(
                    name = annot.name,
                    description = annot.description,
                    isBotOwnerRestricted = annot.isOwnerRestricted,
                    requiredBotPermissions = EnumSet.noneOf(Permission::class.java)!!,
                    requiredUserPermissions = EnumSet.noneOf(Permission::class.java)!!,
                    requiredRole = annot.requiredRole,
                    invoke = member,
                    parentModule = it,
                    parameters = parameters
                ).apply(gatewayCommands::add).apply(eventHandler::onGatewayCommandRegistered)
            }
        }
        commands = gatewayCommands
    }

    override fun onMessageReceived(event: MessageReceivedEvent) {
        GlobalScope.launch(Dispatchers.Default) {
            handleMessageReceived(event)
        }
    }

    suspend fun execute(argsRaw: List<String>, request: GatewayCommandRequest): Result {
        val commandToken = argsRaw[0]
        val command = commands.firstOrNull { it.isMatch(commandToken) } ?: return CommandNotFoundResult(commandToken)
        val args = argsRaw.drop(1)

        // TODO command contracts

        // Parameters
        if ((command.parameters.size) > args.size) {
            return NotEnoughParametersResult(args.size, command.parameters.size)
        }

        val parsedArgs = mutableListOf<Any>()

        for ((i, parameter) in command.parameters.withIndex()) {
            if (parameter.type == String::class) {
                parsedArgs.add(args[i])
                continue
            }
            val typeParser = typeParsers.firstOrNull {
                it::class.supertypes[0].arguments[0].type!!.isSubtypeOf(parameter.type.createType())
            } ?: return ParameterTypeParserMissingResult(parameter.type)
            val parameterValue = args[i]
            try {
                val parsedValue = typeParser.parse(parameterValue, parameter)
                if (!parsedValue.isSuccess) return parsedValue
                parsedArgs.add(parsedValue.result!!)
            } catch (e: Throwable) {
                return ParameterTypeParserExceptionResult(e, parameter.type)
            }
        }

        // Finalization
        try {
            /*val canInvoke = command.canInvoke(request)
            if (!canInvoke.isNullOrEmpty()) {
                eventHandler.onGatewayCommandFailure(command, request, GatewayCommandFailure.FailedCanInvokeCheck, canInvoke)
                return
            }*/
            val message = command.invoke(request, parsedArgs)
            if (message != null) request.channel.trySendMessage(message.build())
            return Result(true, null)
        } catch (e: Throwable) {
            logger.error("Error thrown while processing gateway command ${command.name}", e)
            request.channel.trySendMessage( "There was an internal error running that command. Try again later.")
            return CommandExceptionResult(e, command)
        }
    }

    private suspend fun handleMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) return

        var content = event.message.contentRaw
        val prefix = prefixStrategy.getPrefix(event.guild)

        if (!content.startsWith(prefix, true)) return
        content = content.substring(prefix.length)

        val contentSplit = content.split(" ")

        val request = GatewayCommandRequest(event.guild, event.textChannel, event.member, event.author, contentSplit.drop(1), event.jda, event.message)

        val result = execute(contentSplit, request)

        logger.info(result::class.simpleName!! + " " + (result.reason ?: "No reason"))
    }

    private fun handleOwnerOnlyCommandError(command: GatewayCommandInternal, call: GatewayCommandRequest) {
        eventHandler.onGatewayCommandFailure(command, call, GatewayCommandFailure.FailedOwnerRestrictionCheck, "")
    }

    private fun handleMissingRequiredRole(command: GatewayCommandInternal, call: GatewayCommandRequest, roleId: String) {
        eventHandler.onGatewayCommandFailure(command, call, GatewayCommandFailure.MissingUserRole, call.guild!!.getRoleById(roleId)?.name ?: "")
    }

    private fun handleMissingRequiredUserPermissions(command: GatewayCommandInternal, call: GatewayCommandRequest, permissions: EnumSet<Permission>) {
        eventHandler.onGatewayCommandFailure(command, call, GatewayCommandFailure.MissingUserPermissions, permissions.map { "`${it.name}`" }.joinToString(", "))
    }

    private fun handleMissingRequiredBotPermissions(command: GatewayCommandInternal, call: GatewayCommandRequest, permissions: EnumSet<Permission>) {
        eventHandler.onGatewayCommandFailure(command, call, GatewayCommandFailure.MissingBotPermissions, permissions.map { "`${it.name}`" }.joinToString(", "))
    }

    /**
     * A builder pattern for [CommandEngine] instances.
     */
    class Builder {
        private var prefixStrategy: PrefixStrategy = StaticPrefixStrategy("!")
        private var modules: MutableList<CommandModule> = mutableListOf()
        private var typeParsers: MutableList<TypeParser<*>> = mutableListOf(
            IntTypeParser(), BoolTypeParser()
        )
        private var ownerId: String = ""
        private var engineEventHandler: CommandEngineEventHandler = DefaultCommandEngineEventHandler()

        /**
         * Registers a type parser for type [T].
         */
        fun <T> addTypeParser(typeParser: TypeParser<T>): Builder {
            this.typeParsers.add(typeParser)
            return this
        }

        /**
         * Sets the prefix strategy for the resulting [CommandEngine].
         * Defaults to a [StaticPrefixStrategy] with prefix `!`.
         */
        fun setPrefixStrategy(prefixStrategy: PrefixStrategy): Builder {
            this.prefixStrategy = prefixStrategy
            return this
        }

        /**
         * Adds the supplied [CommandModule] to the resulting [CommandEngine].
         * These modules will be searched for commands.
         */
        fun addModules(vararg modulesToAdd: CommandModule): Builder {
            this.modules.addAll(modulesToAdd)
            return this
        }

        /**
         * Sets the IDs of the owners of the resulting [CommandEngine].
         * This field is required.
         */
        fun setOwnerId(ownerId: String): Builder {
            this.ownerId = ownerId
            return this
        }

        /**
         * Sets the [CommandEngineEventHandler] to use.
         */
        fun setEventHandler(eventHandler: CommandEngineEventHandler): Builder {
            this.engineEventHandler = eventHandler
            return this
        }

        /**
         * Creates a [CommandEngine] instance from this [CommandEngine.Builder].
         */
        fun build(): CommandEngine {
            if (this.ownerId.isEmpty()) throw IllegalArgumentException("CommandEngine.Builder.ownerId is a required property.")
            return CommandEngine(
                prefixStrategy = this.prefixStrategy,
                modules = this.modules,
                ownerId = this.ownerId,
                eventHandler = this.engineEventHandler,
                typeParsers = this.typeParsers
            )
        }
    }
}
package matt.bot.discord.gazer

import net.dv8tion.jda.core.entities.*

fun runCommand(command: String, tokenizer: Tokenizer, sourceMessage: Message)
{
    if(!sourceMessage.channelType.isGuild)
        Command[command].takeIf {it.allowedInPrivateChannel}?.invoke(tokenizer, sourceMessage)
    else
        Command[command](tokenizer, sourceMessage)
}

@Suppress("unused")
sealed class Command(val prefix: String, val requiresAdmin: Boolean = false, val allowedInPrivateChannel: Boolean = false)
{
    companion object
    {
        private val commands = mutableMapOf<String, Command>()
        private val noopCommand: Command
        
        init
        {
            Command::class.sealedSubclasses.asSequence().map {it.constructors.first().call()}.forEach {commands[it.prefix] = it}
            noopCommand = commands.remove("noop")!!
        }
        
        operator fun get(prefix: String) = commands.getOrDefault(prefix, noopCommand)
    }
    
    abstract fun helpMessage(): String
    abstract operator fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
    
    class NoopCommand: Command("noop", allowedInPrivateChannel = true)
    {
        override fun helpMessage() = ""
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message) {}
    }
    
    class Say: Command("say", true)
    {
        override fun helpMessage() = """`${botPrefix}say` __Makes the bot say something__
            |
            |**Usage:** ${botPrefix}say [text]
            |              ${botPrefix}say [text] !tts
            |
            |**Examples:**
            |`${botPrefix}say hello world` makes the bot say 'hello world'
            |`${botPrefix}say hello world !tts` makes the bot say 'hello world' with tts
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            if(isServerAdmin(sourceMessage.member))
            {
                var content = tokenizer.remainingTextAsToken.tokenValue
                val tts = content.endsWith("!tts")
                if(tts)
                    content = content.substring(0, content.length - 4).trim()
                if(content.isNotEmpty())
                {
                    sourceMessage.channel.sendMessage(content).tts(tts).queue()
                    sourceMessage.delete().queue()
                    println("${sourceMessage.author.name} made me say \"$content\"")
                }
                else
                {
                    sourceMessage.channel.sendMessage("I can't say blank messages").queue()
                }
            }
        }
    }
    
    class Help: Command("help", allowedInPrivateChannel = true)
    {
        override fun helpMessage() = """`${botPrefix}help` __Displays a list of commands. Provide a command to get its info__
            |
            |**Usage:** ${botPrefix}help [command]
            |
            |**Examples:**
            |`${botPrefix}help` displays a list of all commands
            |`${botPrefix}help say` displays the help info for the say command
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            val (adminCommands, normalCommands) = commands.values.splitAndMap(Command::requiresAdmin) {it.prefix}
            val message = if(!tokenizer.hasNext())
            {
                """```bash
                    |'command List'```
                    |
                    |Use `!help [command]` to get more info on a specific command, for example: `${botPrefix}help say`
                    |
                    |**Standard Commands**
                    |${normalCommands.joinToString(" ") {"`$it`"}}
                    |
                    |**Admin Commands**
                    |${adminCommands.joinToString(" ") {"`$it`"}}
                """.trimMargin()
            }
            else
            {
                val command = tokenizer.next().tokenValue
                commands[command]?.helpMessage() ?: "Command '$command' was not found."
            }
            sourceMessage.channel.sendMessage(message).queue()
        }
    }
}
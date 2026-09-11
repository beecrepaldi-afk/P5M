"""Impede que uma verificacao incompleta se apresente como compilacao verde."""
import contextlib
import io
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import compilar_nativo as verificar


class ResultadoDoCompilador(unittest.TestCase):
    def executar(self, codigo, mensagem, cabecalhos=True):
        with contextlib.ExitStack() as stack:
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            stack.enter_context(patch.object(verificar.shutil, 'which', return_value='g++'))
            stack.enter_context(patch.object(verificar, 'baixar_reais', return_value=cabecalhos))
            stack.enter_context(patch.object(verificar, 'stubs_android'))
            stack.enter_context(patch.object(verificar.os, 'listdir', return_value=['fonte.cpp']))
            stack.enter_context(patch.object(verificar.subprocess, 'run',
                return_value=SimpleNamespace(returncode=codigo, stderr=mensagem)))
            return verificar.main()

    def test_falha_sem_palavra_error_reprova(self):
        self.assertEqual(self.executar(1, 'processo encerrado pelo sistema'), 1)

    def test_falha_sem_saida_reprova(self):
        self.assertEqual(self.executar(2, ''), 1)

    def test_cabecalhos_ausentes_nao_sao_sucesso(self):
        self.assertEqual(self.executar(0, '', cabecalhos=False), 1)

    def test_compilacao_bem_sucedida(self):
        self.assertEqual(self.executar(0, ''), 0)


if __name__ == '__main__':
    unittest.main()
